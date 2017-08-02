package com.banfftech.cloudcard.pay.tenpay.api;

public class WxPayApiConfigKit {
	private static final ThreadLocal<WxPayApiConfig> tl = new ThreadLocal<WxPayApiConfig>();
	
	public static void setThreadLocalWxPayApiConfig(WxPayApiConfig wxPayApiConfig) {
		tl.set(wxPayApiConfig);
	}
	
	public static void removeThreadLocalApiConfig() {
		tl.remove();
	}
	
	public static WxPayApiConfig getWxPayApiConfig() {
		WxPayApiConfig result = tl.get();
		if (result == null)
			throw new IllegalStateException("需要事先使用 WxPayApiConfigKit.setThreadLocalWxPayApiConfig(wxPayApiConfig) 将 wxPayApiConfig对象存入，才可以调用 WxPayApiConfigKit.getWxPayApiConfig() 方法");
		return result;
	}
}
