package com.banfftech.cloudcard.pay.tenpay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jdom.JDOMException;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.pay.tenpay.util.HttpUtil;
import com.banfftech.cloudcard.pay.tenpay.util.TenpayUtil;
import com.banfftech.cloudcard.pay.tenpay.util.XMLUtil;

public class WeiXinPayServices {
	public static final String resourceError = "cloudcardErrorUiLabels";

	// 获取商品信息
	public static String getProduct(Delegator delegator, Map<String, Object> context) {
		String body = (String) context.get("body");
		String totalFee = (String) context.get("totalFee");
		String tradeType = (String) context.get("tradeType");
		String wxAppID = (String) context.get("wxAppID");
		String wxPartnerid = (String) context.get("wxPartnerid");
		String notifyUrl = (String) context.get("notifyUrl");

		// 获取随机数
		String nonceStr = TenpayUtil.getNonceStr(32);
		//拼接签名参数
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
		parameterMap.put("total_fee", Integer.valueOf(totalFee) * 100);
		parameterMap.put("trade_type", tradeType);
		parameterMap.put("total_fee", "1");
		//签名参数
		String sign = TenpayUtil.createSign("UTF-8", parameterMap, context.get("appKey").toString());
		parameterMap.put("sign", sign);
		String xmlstring = XMLUtil.toXml(parameterMap);
		try {
			xmlstring = new String(xmlstring.toString().getBytes(), "ISO8859-1");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		} catch (IOException e) {
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		// 返回app签名等信息
		String noncestr = TenpayUtil.getNonceStr(32);
		String prepayid = prepayOrderMap.get("prepay_id").toString();
		String timestamp = TenpayUtil.getCurrTime();

		SortedMap<String, Object> parameterMap = new TreeMap<String, Object>();  
		parameterMap.put("appid", wxAppID);
		parameterMap.put("noncestr", noncestr);
		parameterMap.put("package", "Sign=WXPay");
		parameterMap.put("partnerid", wxPartnerid);
		parameterMap.put("prepayid", prepayid);
		parameterMap.put("timestamp", timestamp);
		String sign = TenpayUtil.createSign("UTF-8", parameterMap , appKey);

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
	
	public static void wxOrderNotify(HttpServletRequest request,HttpServletResponse response){
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		Delegator delegator = dispatcher.getDelegator();
		InputStream inStream;
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
			if (map.get("result_code").toString().equalsIgnoreCase("SUCCESS")) {
				String appKey = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
				boolean verifyWeixinNotifySign = verifyWeixinNotify(map,appKey);
				if(verifyWeixinNotifySign){
					//支付成功
				}else{
					//支付失败
				}
			}
			
		} catch (IOException e) {
			e.printStackTrace();
		} catch (JDOMException e) {
			e.printStackTrace();
		}  
		  
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
