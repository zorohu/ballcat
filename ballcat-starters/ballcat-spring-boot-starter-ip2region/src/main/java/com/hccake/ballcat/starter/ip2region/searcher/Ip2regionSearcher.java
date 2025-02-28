package com.hccake.ballcat.starter.ip2region.searcher;

import com.hccake.ballcat.starter.ip2region.core.IpInfo;
import org.springframework.lang.Nullable;

/**
 * IP 搜索
 *
 * @author lishangbu
 * @date 2022/10/16
 */
public interface Ip2regionSearcher {

	/**
	 * ip 位置 搜索
	 * @param ip ip
	 * @return 位置
	 */
	@Nullable
	IpInfo search(long ip);

	/**
	 * ip 位置 搜索
	 * @param ip ip
	 * @return 位置
	 */
	@Nullable
	IpInfo search(String ip);

}
