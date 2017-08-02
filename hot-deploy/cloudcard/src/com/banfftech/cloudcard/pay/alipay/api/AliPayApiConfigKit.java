package com.banfftech.cloudcard.pay.alipay.api;
/**
 * @Email subenkun@gmail.com
 * @author su_bk
 * 2017年8月2日
 */
public class AliPayApiConfigKit {
	private static final ThreadLocal<AliPayApiConfig> tl = new ThreadLocal<AliPayApiConfig>();
	
	public static void setThreadLocalAliPayApiConfig(AliPayApiConfig aliPayApiConfig) {
		tl.set(aliPayApiConfig);
	}
	
	public static void removeThreadLocalApiConfig() {
		tl.remove();
	}
	
	public static AliPayApiConfig getAliPayApiConfig() {
		AliPayApiConfig result = tl.get();
		if (result == null)
			throw new IllegalStateException("需要事先使用 AliPayApiConfigKit.setThreadLocalAliPayApiConfig(aliPayApiConfig) 将 aliPayApiConfig对象存入，才可以调用 AliPayApiConfigKit.getAliPayApiConfig() 方法");
		return result;
	}
}
