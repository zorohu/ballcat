package com.hccake.ballcat.auth.token;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.oauth2.common.ExpiringOAuth2RefreshToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2RefreshToken;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.AuthenticationKeyGenerator;
import org.springframework.security.oauth2.provider.token.DefaultAuthenticationKeyGenerator;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.redis.JdkSerializationStrategy;
import org.springframework.security.oauth2.provider.token.store.redis.RedisTokenStoreSerializationStrategy;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 自定的 RedisTokenStore，用于在发生缓存读取异常时，删除缓存 key
 *
 * @author hccake
 */
@Deprecated
@Slf4j
@SuppressWarnings("all")
public class CustomRedisTokenStore implements TokenStore {

	private static final String ACCESS = "access:";

	private static final String AUTH_TO_ACCESS = "auth_to_access:";

	private static final String AUTH = "auth:";

	private static final String REFRESH_AUTH = "refresh_auth:";

	private static final String ACCESS_TO_REFRESH = "access_to_refresh:";

	private static final String REFRESH = "refresh:";

	private static final String REFRESH_TO_ACCESS = "refresh_to_access:";

	private static final String CLIENT_ID_TO_ACCESS = "client_id_to_access:";

	private static final String UNAME_TO_ACCESS = "uname_to_access:";

	private static final boolean springDataRedis_2_0 = ClassUtils.isPresent(
			"org.springframework.data.redis.connection.RedisStandaloneConfiguration",
			org.springframework.security.oauth2.provider.token.store.redis.RedisTokenStore.class.getClassLoader());

	private final RedisConnectionFactory connectionFactory;

	private AuthenticationKeyGenerator authenticationKeyGenerator = new DefaultAuthenticationKeyGenerator();

	private RedisTokenStoreSerializationStrategy serializationStrategy = new JdkSerializationStrategy();

	private String prefix = "";

	private Method redisConnectionSet_2_0;

	public CustomRedisTokenStore(RedisConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
		if (springDataRedis_2_0) {
			this.loadRedisConnectionMethods_2_0();
		}
	}

	public void setAuthenticationKeyGenerator(AuthenticationKeyGenerator authenticationKeyGenerator) {
		this.authenticationKeyGenerator = authenticationKeyGenerator;
	}

	public void setSerializationStrategy(RedisTokenStoreSerializationStrategy serializationStrategy) {
		this.serializationStrategy = serializationStrategy;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	private void loadRedisConnectionMethods_2_0() {
		this.redisConnectionSet_2_0 = ReflectionUtils.findMethod(RedisConnection.class, "set", byte[].class,
				byte[].class);
	}

	private RedisConnection getConnection() {
		return connectionFactory.getConnection();
	}

	private byte[] serialize(Object object) {
		return serializationStrategy.serialize(object);
	}

	private byte[] serializeKey(String object) {
		return serialize(prefix + object);
	}

	private OAuth2AccessToken deserializeAccessToken(byte[] bytes) {
		return serializationStrategy.deserialize(bytes, OAuth2AccessToken.class);
	}

	private OAuth2Authentication deserializeAuthentication(byte[] bytes) {
		return serializationStrategy.deserialize(bytes, OAuth2Authentication.class);
	}

	private OAuth2RefreshToken deserializeRefreshToken(byte[] bytes) {
		return serializationStrategy.deserialize(bytes, OAuth2RefreshToken.class);
	}

	private byte[] serialize(String string) {
		return serializationStrategy.serialize(string);
	}

	private String deserializeString(byte[] bytes) {
		return serializationStrategy.deserializeString(bytes);
	}

	@Override
	public OAuth2AccessToken getAccessToken(OAuth2Authentication authentication) {
		String key = authenticationKeyGenerator.extractKey(authentication);
		byte[] serializedKey = serializeKey(AUTH_TO_ACCESS + key);
		byte[] bytes = null;
		RedisConnection conn = getConnection();
		try {
			bytes = conn.get(serializedKey);
		}
		finally {
			conn.close();
		}

		// ==== 当序列化异常时，删除缓存 key ====
		OAuth2AccessToken accessToken = null;
		try {
			accessToken = deserializeAccessToken(bytes);
		}
		catch (SerializationException e) {
			log.warn("[getAccessToken] OAuth2AccessToken 序列化异常, key: [{}]", key, e);
			conn.del(bytes);
		}

		if (accessToken != null) {
			OAuth2Authentication storedAuthentication = readAuthentication(accessToken.getValue());
			if ((storedAuthentication == null
					|| !key.equals(authenticationKeyGenerator.extractKey(storedAuthentication)))) {
				// Keep the stores consistent (maybe the same user is
				// represented by this authentication but the details have
				// changed)
				storeAccessToken(accessToken, authentication);
			}

		}
		return accessToken;
	}

	@Override
	public OAuth2Authentication readAuthentication(OAuth2AccessToken token) {
		return readAuthentication(token.getValue());
	}

	@Override
	public OAuth2Authentication readAuthentication(String token) {
		byte[] bytes = null;
		RedisConnection conn = getConnection();
		byte[] key = serializeKey(AUTH + token);

		try {
			bytes = conn.get(key);
		}
		finally {
			conn.close();
		}

		// ==== 当序列化异常时，删除缓存 key ====
		OAuth2Authentication auth = null;
		try {
			auth = deserializeAuthentication(bytes);
		}
		catch (SerializationException e) {
			log.warn("[readAuthentication] OAuth2Authentication 序列化异常, token: [{}]", token, e);
			removeAccessToken(token);
		}

		return auth;
	}

	@Override
	public OAuth2Authentication readAuthenticationForRefreshToken(OAuth2RefreshToken token) {
		return readAuthenticationForRefreshToken(token.getValue());
	}

	public OAuth2Authentication readAuthenticationForRefreshToken(String token) {
		RedisConnection conn = getConnection();
		try {
			byte[] bytes = conn.get(serializeKey(REFRESH_AUTH + token));
			OAuth2Authentication auth = deserializeAuthentication(bytes);
			return auth;
		}
		finally {
			conn.close();
		}
	}

	@Override
	public void storeAccessToken(OAuth2AccessToken token, OAuth2Authentication authentication) {
		byte[] serializedAccessToken = serialize(token);
		byte[] serializedAuth = serialize(authentication);
		byte[] accessKey = serializeKey(ACCESS + token.getValue());
		byte[] authKey = serializeKey(AUTH + token.getValue());
		byte[] authToAccessKey = serializeKey(AUTH_TO_ACCESS + authenticationKeyGenerator.extractKey(authentication));
		byte[] approvalKey = serializeKey(UNAME_TO_ACCESS + getApprovalKey(authentication));
		byte[] clientId = serializeKey(CLIENT_ID_TO_ACCESS + authentication.getOAuth2Request().getClientId());

		RedisConnection conn = getConnection();
		try {
			conn.openPipeline();
			if (springDataRedis_2_0) {
				try {
					this.redisConnectionSet_2_0.invoke(conn, accessKey, serializedAccessToken);
					this.redisConnectionSet_2_0.invoke(conn, authKey, serializedAuth);
					this.redisConnectionSet_2_0.invoke(conn, authToAccessKey, serializedAccessToken);
				}
				catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}
			else {
				conn.set(accessKey, serializedAccessToken);
				conn.set(authKey, serializedAuth);
				conn.set(authToAccessKey, serializedAccessToken);
			}
			if (!authentication.isClientOnly()) {
				conn.sAdd(approvalKey, serializedAccessToken);
			}
			conn.sAdd(clientId, serializedAccessToken);
			if (token.getExpiration() != null) {
				int seconds = token.getExpiresIn();
				conn.expire(accessKey, seconds);
				conn.expire(authKey, seconds);
				conn.expire(authToAccessKey, seconds);
				conn.expire(clientId, seconds);
				conn.expire(approvalKey, seconds);
			}
			OAuth2RefreshToken refreshToken = token.getRefreshToken();
			if (refreshToken != null && refreshToken.getValue() != null) {
				byte[] refresh = serialize(token.getRefreshToken().getValue());
				byte[] auth = serialize(token.getValue());
				byte[] refreshToAccessKey = serializeKey(REFRESH_TO_ACCESS + token.getRefreshToken().getValue());
				byte[] accessToRefreshKey = serializeKey(ACCESS_TO_REFRESH + token.getValue());
				if (springDataRedis_2_0) {
					try {
						this.redisConnectionSet_2_0.invoke(conn, refreshToAccessKey, auth);
						this.redisConnectionSet_2_0.invoke(conn, accessToRefreshKey, refresh);
					}
					catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}
				else {
					conn.set(refreshToAccessKey, auth);
					conn.set(accessToRefreshKey, refresh);
				}
				if (refreshToken instanceof ExpiringOAuth2RefreshToken) {
					ExpiringOAuth2RefreshToken expiringRefreshToken = (ExpiringOAuth2RefreshToken) refreshToken;
					Date expiration = expiringRefreshToken.getExpiration();
					if (expiration != null) {
						int seconds = Long.valueOf((expiration.getTime() - System.currentTimeMillis()) / 1000L)
								.intValue();
						conn.expire(refreshToAccessKey, seconds);
						conn.expire(accessToRefreshKey, seconds);
					}
				}
			}
			conn.closePipeline();
		}
		finally {
			conn.close();
		}
	}

	private static String getApprovalKey(OAuth2Authentication authentication) {
		String userName = authentication.getUserAuthentication() == null ? ""
				: authentication.getUserAuthentication().getName();
		return getApprovalKey(authentication.getOAuth2Request().getClientId(), userName);
	}

	private static String getApprovalKey(String clientId, String userName) {
		return clientId + (userName == null ? "" : ":" + userName);
	}

	@Override
	public void removeAccessToken(OAuth2AccessToken accessToken) {
		removeAccessToken(accessToken.getValue());
	}

	@Override
	public OAuth2AccessToken readAccessToken(String tokenValue) {
		byte[] key = serializeKey(ACCESS + tokenValue);
		byte[] bytes = null;
		RedisConnection conn = getConnection();
		try {
			bytes = conn.get(key);
		}
		finally {
			conn.close();
		}

		// ==== 当序列化异常时，删除缓存 key ====
		OAuth2AccessToken accessToken = null;
		try {
			accessToken = deserializeAccessToken(bytes);
		}
		catch (SerializationException e) {
			log.warn("[readAccessToken] OAuth2AccessToken 序列化异常, token: [{}]", tokenValue, e);
			removeAccessToken(tokenValue);
		}

		return accessToken;
	}

	public void removeAccessToken(String tokenValue) {
		byte[] accessKey = serializeKey(ACCESS + tokenValue);
		byte[] authKey = serializeKey(AUTH + tokenValue);
		byte[] accessToRefreshKey = serializeKey(ACCESS_TO_REFRESH + tokenValue);
		RedisConnection conn = getConnection();
		try {
			conn.openPipeline();
			conn.get(accessKey);
			conn.get(authKey);
			conn.del(accessKey);
			conn.del(accessToRefreshKey);
			// Don't remove the refresh token - it's up to the caller to do that
			conn.del(authKey);
			List<Object> results = conn.closePipeline();
			byte[] access = (byte[]) results.get(0);
			byte[] auth = (byte[]) results.get(1);

			// ==== 当序列化异常时，删除缓存 key ====
			OAuth2Authentication authentication = null;
			try {
				authentication = deserializeAuthentication(auth);
			}
			catch (SerializationException e) {
				log.warn("[removeAccessToken] OAuth2Authentication 序列化异常", e);
			}

			if (authentication != null) {
				String key = authenticationKeyGenerator.extractKey(authentication);
				byte[] authToAccessKey = serializeKey(AUTH_TO_ACCESS + key);
				byte[] unameKey = serializeKey(UNAME_TO_ACCESS + getApprovalKey(authentication));
				byte[] clientId = serializeKey(CLIENT_ID_TO_ACCESS + authentication.getOAuth2Request().getClientId());
				conn.openPipeline();
				conn.del(authToAccessKey);
				conn.sRem(unameKey, access);
				conn.sRem(clientId, access);
				conn.del(serialize(ACCESS + key));
				conn.closePipeline();
			}
		}
		finally {
			conn.close();
		}
	}

	@Override
	public void storeRefreshToken(OAuth2RefreshToken refreshToken, OAuth2Authentication authentication) {
		byte[] refreshKey = serializeKey(REFRESH + refreshToken.getValue());
		byte[] refreshAuthKey = serializeKey(REFRESH_AUTH + refreshToken.getValue());
		byte[] serializedRefreshToken = serialize(refreshToken);
		RedisConnection conn = getConnection();
		try {
			conn.openPipeline();
			if (springDataRedis_2_0) {
				try {
					this.redisConnectionSet_2_0.invoke(conn, refreshKey, serializedRefreshToken);
					this.redisConnectionSet_2_0.invoke(conn, refreshAuthKey, serialize(authentication));
				}
				catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}
			else {
				conn.set(refreshKey, serializedRefreshToken);
				conn.set(refreshAuthKey, serialize(authentication));
			}
			if (refreshToken instanceof ExpiringOAuth2RefreshToken) {
				ExpiringOAuth2RefreshToken expiringRefreshToken = (ExpiringOAuth2RefreshToken) refreshToken;
				Date expiration = expiringRefreshToken.getExpiration();
				if (expiration != null) {
					int seconds = Long.valueOf((expiration.getTime() - System.currentTimeMillis()) / 1000L).intValue();
					conn.expire(refreshKey, seconds);
					conn.expire(refreshAuthKey, seconds);
				}
			}
			conn.closePipeline();
		}
		finally {
			conn.close();
		}
	}

	@Override
	public OAuth2RefreshToken readRefreshToken(String tokenValue) {
		byte[] key = serializeKey(REFRESH + tokenValue);
		byte[] bytes = null;
		RedisConnection conn = getConnection();
		try {
			bytes = conn.get(key);
		}
		finally {
			conn.close();
		}
		OAuth2RefreshToken refreshToken = deserializeRefreshToken(bytes);
		return refreshToken;
	}

	@Override
	public void removeRefreshToken(OAuth2RefreshToken refreshToken) {
		removeRefreshToken(refreshToken.getValue());
	}

	public void removeRefreshToken(String tokenValue) {
		byte[] refreshKey = serializeKey(REFRESH + tokenValue);
		byte[] refreshAuthKey = serializeKey(REFRESH_AUTH + tokenValue);
		byte[] refresh2AccessKey = serializeKey(REFRESH_TO_ACCESS + tokenValue);
		byte[] access2RefreshKey = serializeKey(ACCESS_TO_REFRESH + tokenValue);
		RedisConnection conn = getConnection();
		try {
			conn.openPipeline();
			conn.del(refreshKey);
			conn.del(refreshAuthKey);
			conn.del(refresh2AccessKey);
			conn.del(access2RefreshKey);
			conn.closePipeline();
		}
		finally {
			conn.close();
		}
	}

	@Override
	public void removeAccessTokenUsingRefreshToken(OAuth2RefreshToken refreshToken) {
		removeAccessTokenUsingRefreshToken(refreshToken.getValue());
	}

	private void removeAccessTokenUsingRefreshToken(String refreshToken) {
		byte[] key = serializeKey(REFRESH_TO_ACCESS + refreshToken);
		List<Object> results = null;
		RedisConnection conn = getConnection();
		try {
			conn.openPipeline();
			conn.get(key);
			conn.del(key);
			results = conn.closePipeline();
		}
		finally {
			conn.close();
		}
		if (results == null) {
			return;
		}
		byte[] bytes = (byte[]) results.get(0);
		String accessToken = deserializeString(bytes);
		if (accessToken != null) {
			removeAccessToken(accessToken);
		}
	}

	private List<byte[]> getByteLists(byte[] approvalKey, RedisConnection conn) {
		List<byte[]> byteList;
		Long size = conn.sCard(approvalKey);
		byteList = new ArrayList<byte[]>(size.intValue());
		Cursor<byte[]> cursor = conn.sScan(approvalKey, ScanOptions.NONE);
		while (cursor.hasNext()) {
			byteList.add(cursor.next());
		}
		return byteList;
	}

	@Override
	public Collection<OAuth2AccessToken> findTokensByClientIdAndUserName(String clientId, String userName) {
		byte[] approvalKey = serializeKey(UNAME_TO_ACCESS + getApprovalKey(clientId, userName));
		List<byte[]> byteList = null;
		RedisConnection conn = getConnection();
		try {
			byteList = getByteLists(approvalKey, conn);
		}
		finally {
			conn.close();
		}
		if (byteList == null || byteList.size() == 0) {
			return Collections.<OAuth2AccessToken>emptySet();
		}
		List<OAuth2AccessToken> accessTokens = new ArrayList<OAuth2AccessToken>(byteList.size());
		for (byte[] bytes : byteList) {
			OAuth2AccessToken accessToken = deserializeAccessToken(bytes);
			accessTokens.add(accessToken);
		}
		return Collections.<OAuth2AccessToken>unmodifiableCollection(accessTokens);
	}

	@Override
	public Collection<OAuth2AccessToken> findTokensByClientId(String clientId) {
		byte[] key = serializeKey(CLIENT_ID_TO_ACCESS + clientId);
		List<byte[]> byteList = null;
		RedisConnection conn = getConnection();
		try {
			byteList = getByteLists(key, conn);
		}
		finally {
			conn.close();
		}
		if (byteList == null || byteList.size() == 0) {
			return Collections.<OAuth2AccessToken>emptySet();
		}
		List<OAuth2AccessToken> accessTokens = new ArrayList<OAuth2AccessToken>(byteList.size());
		for (byte[] bytes : byteList) {
			OAuth2AccessToken accessToken = deserializeAccessToken(bytes);
			accessTokens.add(accessToken);
		}
		return Collections.<OAuth2AccessToken>unmodifiableCollection(accessTokens);
	}

}
