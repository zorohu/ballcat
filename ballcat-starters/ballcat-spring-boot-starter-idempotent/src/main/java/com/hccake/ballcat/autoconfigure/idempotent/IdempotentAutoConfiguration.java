package com.hccake.ballcat.autoconfigure.idempotent;

import com.hccake.ballcat.common.idempotent.IdempotentAspect;
import com.hccake.ballcat.common.idempotent.key.generator.DefaultKeyGenerator;
import com.hccake.ballcat.common.idempotent.key.generator.KeyGenerator;
import com.hccake.ballcat.common.idempotent.key.store.IdempotentKeyStore;
import com.hccake.ballcat.common.idempotent.key.store.InMemoryIdempotentKeyStore;
import com.hccake.ballcat.common.idempotent.key.store.RedisIdempotentKeyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 幂等自动装配
 *
 * @author lishangbu
 * @date 2022/10/18
 */
@AutoConfiguration
@RequiredArgsConstructor
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentAutoConfiguration {

	/**
	 * 默认的幂等前缀生成器
	 * @return 幂等Key生成器
	 */
	@Bean
	@ConditionalOnMissingBean
	public KeyGenerator keyGenerator() {
		return new DefaultKeyGenerator();
	}

	/**
	 * 默认的幂等键存储器
	 * @param properties 幂等属性配置
	 * @return 幂等Key存储
	 */
	@Bean
	@ConditionalOnMissingBean
	public IdempotentKeyStore idempotentKeyStore(IdempotentProperties properties) {
		IdempotentProperties.KeyStoreType keyStoreType = properties.getKeyStoreType();
		if (keyStoreType.equals(IdempotentProperties.KeyStoreType.REDIS)) {
			return new RedisIdempotentKeyStore();
		}
		else {
			return new InMemoryIdempotentKeyStore();
		}
	}

	/**
	 * 幂等切面
	 * @param idempotentKeyStore
	 * @return
	 */
	@Bean
	public IdempotentAspect idempotentAspect(IdempotentKeyStore idempotentKeyStore, KeyGenerator keyGenerator) {
		return new IdempotentAspect(idempotentKeyStore, keyGenerator);
	}

}
