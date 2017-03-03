package com.banfftech.cloudcard.pay.tenpay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jdom.JDOMException;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.pay.tenpay.util.HttpUtil;
import com.banfftech.cloudcard.pay.tenpay.util.TenpayUtil;
import com.banfftech.cloudcard.pay.tenpay.util.XMLUtil;

import javolution.util.FastMap;

public class WeiXinPayServices {
	public static final String resourceError = "cloudcardErrorUiLabels";
	public static final String  module = WeiXinPayServices.class.getName();
	
	

	// 获取商品信息
	public static String getProduct(Delegator delegator, Map<String, Object> context) {
		String body = (String) context.get("body");
		String totalFee = (String) context.get("totalFee");
		String tradeType = (String) context.get("tradeType");
		String wxAppID = (String) context.get("wxAppID");
		String wxPartnerid = (String) context.get("wxPartnerid");
		String notifyUrl = (String) context.get("notifyUrl");
//		String paymentService = (String) context.get("paymentService");
		String receiptPaymentId = (String) context.get("receiptPaymentId");
		String cardId= (String) context.get("cardId");
		String storeId= (String) context.get("storeId");

		// 获取随机数
		String nonceStr = TenpayUtil.getNonceStr(32);
		// 拼接签名参数
		StringBuffer xml = new StringBuffer();
		xml.append("</xml>");

		SortedMap<String, Object> parameterMap = new TreeMap<String, Object>();
		parameterMap.put("appid", wxAppID);
		parameterMap.put("mch_id", wxPartnerid);
		parameterMap.put("body", body);
		parameterMap.put("nonce_str", nonceStr);
		parameterMap.put("notify_url", notifyUrl);
		parameterMap.put("out_trade_no", TenpayUtil.getCurrTime());
		parameterMap.put("spbill_create_ip", "127.0.0.1");
        parameterMap.put("total_fee", Double.valueOf(totalFee) * 100 );
		parameterMap.put("trade_type", tradeType);
        parameterMap.put("attach", receiptPaymentId + "," + cardId + "," + storeId);

		// 签名参数
		String sign = TenpayUtil.createSign("UTF-8", parameterMap, context.get("appKey").toString());
		parameterMap.put("sign", sign);
		String xmlstring = XMLUtil.toXml(parameterMap);
//		try {
//			xmlstring = new String(xmlstring.toString().getBytes(), "ISO8859-1");
//		} catch (UnsupportedEncodingException e) {
//			e.printStackTrace();
//		}
		return xmlstring;

	}

	/**
	 * 预支付订单
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> prepayOrder(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String wxAppID = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxAppID", delegator);
		String wxPartnerid = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxPartnerid", delegator);
		String wxURL = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxURL", delegator);
		String appKey = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
		String notifyUrl = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.notifyUrl", delegator);
		
		context.put("wxAppID", wxAppID);
		context.put("wxPartnerid", wxPartnerid);
		context.put("wxURL", wxURL);
		context.put("appKey", appKey);
		context.put("notifyUrl", notifyUrl);

		// 统一下单
		String url = String.format(wxURL);
		String entity = getProduct(delegator, context);
		String buf = HttpUtil.sendPostUrl(url, entity);
		String content = new String(buf);
		Map<String, String> prepayOrderMap = null;
		try {
			prepayOrderMap = XMLUtil.doXMLParse(content);
		} catch (JDOMException e) {
			return ServiceUtil
					.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		} catch (IOException e) {
			return ServiceUtil
					.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		// 返回app签名等信息
		String noncestr = TenpayUtil.getNonceStr(32);
		String prepayid = prepayOrderMap.get("prepay_id");
		String timestamp = String.valueOf(System.currentTimeMillis()/1000);
//TenpayUtil.getCurrTime();
		
		SortedMap<String, Object> parameterMap = new TreeMap<String, Object>();
		parameterMap.put("appid", wxAppID);
		parameterMap.put("noncestr", noncestr);
		parameterMap.put("package", "Sign=WXPay");
		parameterMap.put("partnerid", wxPartnerid);
		parameterMap.put("prepayid", prepayid);
		parameterMap.put("timestamp", timestamp);
		String sign = TenpayUtil.createSign("UTF-8", parameterMap, appKey);

		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("appid", wxAppID);
		results.put("noncestr", noncestr);
		results.put("partnerid", wxPartnerid);
		results.put("package", "Sign=WXPay");
		results.put("prepayid", prepayid);
		results.put("timestamp", timestamp);
		results.put("sign", sign);

		return results;
	}
	
	/**
	 * 微信支付回调接口
	 * @param request
	 * @param response
	 */
	public static void wxPayNotify(HttpServletRequest request, HttpServletResponse response) {
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		Delegator delegator = dispatcher.getDelegator();
		InputStream inStream;
		String wxReturn = null;
		try {
			inStream = request.getInputStream();
			ByteArrayOutputStream outSteam = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int len = 0;
			while ((len = inStream.read(buffer)) != -1) {
				outSteam.write(buffer, 0, len);
			}
			outSteam.close();
			inStream.close();
			String result = new String(outSteam.toByteArray(), "utf-8");// 获取微信调用我们notify_url的返回信息
			Map<Object, Object> map = XMLUtil.doXMLParse(result);
			if (map.get("return_code").toString().equalsIgnoreCase("SUCCESS")) {
				String appKey = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
				boolean verifyWeixinNotifySign = verifyWeixinNotify(map, appKey);
				if (verifyWeixinNotifySign) {
					// 支付成功
				     if( "SUCCESS".equalsIgnoreCase((String) map.get("result_code"))){

                        String attach = (String) map.get("attach");
                        String[] arr = attach.split(",");
                        String paymentId = "";
                        String cardId = "";
                        String storeId = "";
                        if (arr.length >= 3) {
                            paymentId = arr[0];
                            cardId = arr[1];
                            storeId = arr[2];
                        }

				         GenericValue systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
                         Map<String, Object> rechargeCloudCardDepositOutMap = dispatcher.runSync("rechargeCloudCardDeposit", UtilMisc.toMap("userLogin",
                                 systemUserLogin, "cardId", cardId, "receiptPaymentId", paymentId, "organizationPartyId", storeId));

                         if (!ServiceUtil.isSuccess(rechargeCloudCardDepositOutMap)) {
                             // TODO 平台入账 不成功 发起退款
                         }
				         
				     }else{
				         //支付失败
				         
				     }
				    
				    
					SortedMap<String,Object> sort=new TreeMap<String,Object>();
					sort.put("return_code", "SUCCESS");
					sort.put("return_msg", "OK");
					wxReturn = XMLUtil.toXml(sort);
				} else {
					// 支付失败
					
					SortedMap<String,Object> sort=new TreeMap<String,Object>();
					sort.put("return_code", "FAIL");
					sort.put("return_msg", "签名失败");
					wxReturn = XMLUtil.toXml(sort);
				}
			}

		} catch (Exception e) {
		    Debug.logError(e.getMessage(), module);
		}
		
		//返回给微信
		try {
			response.setHeader("content-type", "text/xml;charset=UTF-8");
			response.getWriter().write(wxReturn);
			response.getWriter().flush();
		} catch (IOException e) {
		    Debug.logError(e.getMessage(), module);
		}
	}
	
	/**
	 * 微信订单查询接口
	 * @param delegator
	 * @param context
	 * @return
	 */
	public static Map<String, Object> orderPayQuery(Delegator delegator, Map<String, Object> context) {
		String wxAppID = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxAppID", delegator);
		String wxPartnerid = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxPartnerid", delegator);
		String appKey = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
		String outTradeNo = (String) context.get("outTradeNo");
		String tradeNo = (String) context.get("transactionId");
		SortedMap<String, Object> params = new TreeMap<String, Object>();
		params.put("appid", wxAppID);
		params.put("mch_id", wxPartnerid);
		params.put("nonce_str", TenpayUtil.getNonceStr(32)); // 生成随机串
		
		if(UtilValidate.isNotEmpty(outTradeNo)){
			params.put("out_trade_no", outTradeNo);
		}
		
		if(UtilValidate.isNotEmpty(tradeNo)){
			params.put("transaction_id", tradeNo);
		}

		// 附加签名
		String sign = TenpayUtil.createSign("UTF-8", params, appKey);
		params.put("sign", sign);
		// 转换成XML字符串
		String xmlString = XMLUtil.toXml(params);
		String ret = HttpUtil.sendPostUrl("https://api.mch.weixin.qq.com/pay/orderquery", xmlString);
		try {
			ret = new String( ret.getBytes("GBK"), "UTF-8");
		} catch (UnsupportedEncodingException e1) {
			e1.printStackTrace();
		}
		Map<String, Object> orderPayMap = FastMap.newInstance();
		try {
			Map<String ,Object> orders = XMLUtil.doXMLParse(ret);
			if("SUCCESS".equals(orders.get("result_code"))){
				orderPayMap = ServiceUtil.returnSuccess();
				orderPayMap.put("returnCode", orders.get("return_code"));
				orderPayMap.put("returnMsg", orders.get("return_msg"));
				orderPayMap.put("tradeType", orders.get("trade_type"));
				orderPayMap.put("cashFee", Double.valueOf(orders.get("cash_fee").toString())/100);
				orderPayMap.put("tradeNo", orders.get("out_trade_no"));
				orderPayMap.put("timeEnd", orders.get("time_end"));
				orderPayMap.put("tradeState", orders.get("trade_state"));
			}
			
			if("FAIL".equals(orders.get("result_code"))){
				orderPayMap = ServiceUtil.returnSuccess();
				if("ORDERNOTEXIST".equals(orders.get("err_code"))){
					orderPayMap.put("tradeState", "订单不存在");
				}
			}
		} catch (JDOMException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return orderPayMap;
	}

	/**
	 * 验证微信支付返回结果
	 * 
	 * @param map
	 * @return
	 */
	public static boolean verifyWeixinNotify(Map<Object, Object> map, String appKey) {
		SortedMap<String, Object> parameterMap = new TreeMap<String, Object>();
		String sign = (String) map.get("sign");
		for (Object keyValue : map.keySet()) {
			if (!keyValue.toString().equals("sign")) {
				parameterMap.put(keyValue.toString(), map.get(keyValue));
			}

		}
		String createSign = TenpayUtil.createSign("UTF-8", parameterMap, appKey);
		if (createSign.equals(sign)) {
			return true;
		} else {
			return false;
		}
	}
	
}
