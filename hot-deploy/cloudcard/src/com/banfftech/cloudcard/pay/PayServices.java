package com.banfftech.cloudcard.pay;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.service.DispatchContext;

import com.banfftech.cloudcard.pay.alipay.AliPayServices;
import com.banfftech.cloudcard.pay.tenpay.WeiXinPayServices;

import javolution.util.FastMap;

public class PayServices {
	//微信支付宝统一下单
	public static Map<String, Object> uniformOrder(DispatchContext dctx, Map<String, Object> context) {
		String paymentType = (String) context.get("paymentType");
		Map<String, Object> result = FastMap.newInstance();
		if("wxPay".equals(paymentType)){
			result = WeiXinPayServices.prepayOrder(dctx, context);
		}else if("aliPay".equals(paymentType)){
			result = AliPayServices.prepayOrder(dctx, context);
		}
		return result;
	}
	
	//支付宝支付通知
	public static void aliPayNotify(HttpServletRequest request,HttpServletResponse response){
		AliPayServices.aliPayNotify(request, response);
	}
	
	//微信支付通知
	public static void wxPayNotify(HttpServletRequest request,HttpServletResponse response){
		WeiXinPayServices.wxPayNotify(request, response);
	}
}