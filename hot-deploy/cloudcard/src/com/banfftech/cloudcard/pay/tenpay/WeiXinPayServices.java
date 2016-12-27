package com.banfftech.cloudcard.pay.tenpay;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Map;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.apache.http.message.BasicNameValuePair;
import org.jdom.JDOMException;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;
import org.apache.http.NameValuePair;

import com.banfftech.cloudcard.pay.tenpay.util.HttpUtil;
import com.banfftech.cloudcard.pay.tenpay.util.MD5Util;
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
		List<NameValuePair> packageParams = new LinkedList<NameValuePair>();
		packageParams.add(new BasicNameValuePair("appid", wxAppID));
		// body为汉字是要转成UTF-8
		packageParams.add(new BasicNameValuePair("body", body));
		packageParams.add(new BasicNameValuePair("mch_id", wxPartnerid));
		packageParams.add(new BasicNameValuePair("nonce_str", nonceStr));
		packageParams.add(new BasicNameValuePair("notify_url", notifyUrl));
		packageParams.add(new BasicNameValuePair("out_trade_no", TenpayUtil.getCurrTime()));
		packageParams.add(new BasicNameValuePair("spbill_create_ip", "127.0.0.1"));
		packageParams.add(new BasicNameValuePair("total_fee", totalFee));
		packageParams.add(new BasicNameValuePair("trade_type", tradeType));
		String sign = TenpayUtil.genPackageSign(context, packageParams);
		packageParams.add(new BasicNameValuePair("sign", sign));
		String xmlstring = XMLUtil.toXml(packageParams);
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
		String key = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
		String notifyUrl = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.notifyUrl", delegator);

		context.put("wxAppID", wxAppID);
		context.put("wxPartnerid", wxPartnerid);
		context.put("wxURL", wxURL);
		context.put("key", key);
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
		String prepayid = prepayOrderMap.get("prepay_id").toString();
		String timestamp = TenpayUtil.getCurrTime();

		List<NameValuePair> orderList = new LinkedList<NameValuePair>();
		orderList.add(new BasicNameValuePair("appid", wxAppID));
		orderList.add(new BasicNameValuePair("noncestr", noncestr));
		orderList.add(new BasicNameValuePair("package", "Sign=WXPay"));
		orderList.add(new BasicNameValuePair("partnerid", wxPartnerid));
		orderList.add(new BasicNameValuePair("prepayid", prepayid));
		orderList.add(new BasicNameValuePair("timestamp", timestamp));
		String sign = TenpayUtil.genAppSign(context, orderList);

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

}
