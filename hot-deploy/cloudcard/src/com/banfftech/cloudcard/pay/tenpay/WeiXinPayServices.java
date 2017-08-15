package com.banfftech.cloudcard.pay.tenpay;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.alipay.api.domain.AlipayFundTransToaccountTransferModel;
import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.pay.alipay.AliPayServices;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApi;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApiConfigKit;
import com.banfftech.cloudcard.pay.tenpay.api.WxPayApi;
import com.banfftech.cloudcard.pay.tenpay.api.WxPayApiConfig;
import com.banfftech.cloudcard.pay.tenpay.api.WxPayApiConfig.PayModel;
import com.banfftech.cloudcard.pay.tenpay.api.WxPayApiConfigKit;
import com.banfftech.cloudcard.pay.tenpay.util.PaymentKit;
import com.banfftech.cloudcard.pay.tenpay.util.XMLUtil;
import com.banfftech.cloudcard.pay.util.StringUtils;

import javolution.util.FastMap;

public class WeiXinPayServices {
	public static final String resourceError = "cloudcardErrorUiLabels";
	public static final String  module = WeiXinPayServices.class.getName();
	
	public static void getApiConfig(String appId, String mchId,String partnerKey ) {
		WxPayApiConfig apiConfig = WxPayApiConfig.New()
				.setAppId(appId)
				.setMchId(mchId)
				.setPaternerKey(partnerKey)
				.setPayModel(PayModel.BUSINESSMODEL);
		WxPayApiConfigKit.setThreadLocalWxPayApiConfig(apiConfig);
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
		String partnerKey = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
		String notifyUrl = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.notifyUrl", delegator);

		String body = (String) context.get("body");
		String totalFee = (String) context.get("totalFee");
		String receiptPaymentId = (String) context.get("receiptPaymentId");
		String cardId= (String) context.get("cardId");
		String storeId= (String) context.get("storeId");
		
		getApiConfig(wxAppID, wxPartnerid, partnerKey);
		
		Map<String, String> params = WxPayApiConfigKit.getWxPayApiConfig()
				.setAttach(receiptPaymentId + "," + cardId + "," + storeId)
				.setBody(body)
				.setSpbillCreateIp("127.0.0.1")
				.setTotalFee(String.valueOf(Math.round((Double.valueOf(totalFee) * 100))))
				.setTradeType(WxPayApi.TradeType.APP)
				.setNotifyUrl(notifyUrl)
				.build();
				
		String xmlResult =  WxPayApi.pushOrder(params);
		
		Map<String, String> result = PaymentKit.xmlToMap(xmlResult);
		
		String return_code = result.get("return_code");
		String return_msg = result.get("return_msg");
		if (!PaymentKit.codeIsOK(return_code)) {
			Debug.logError(return_msg,module);
		}
		String result_code = result.get("result_code");
		if (!PaymentKit.codeIsOK(result_code)) {
			Debug.logError(return_msg,module);
		}
		// 以下字段在return_code 和result_code都为SUCCESS的时候有返回
		String prepay_id = result.get("prepay_id");
		//封装调起微信支付的参数 https://pay.weixin.qq.com/wiki/doc/api/app/app.php?chapter=9_12
		
		String noncestr = StringUtils.getNonceStr(32);
		String timestamp = String.valueOf(System.currentTimeMillis()/1000);
		
		Map<String, String> orderMap = FastMap.newInstance();
		orderMap.put("appid", wxAppID);
		orderMap.put("partnerid", wxPartnerid);
		orderMap.put("package", "Sign=WXPay");
		orderMap.put("prepayid", prepay_id);
		orderMap.put("noncestr", noncestr);
		orderMap.put("timestamp", timestamp);
		String packageSign = PaymentKit.createSign(orderMap, WxPayApiConfigKit.getWxPayApiConfig().getPaternerKey());
		WxPayApiConfigKit.removeThreadLocalApiConfig();

		//返回给app
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("appid", wxAppID);
		results.put("partnerid", wxPartnerid);
		results.put("package", "Sign=WXPay");
		results.put("prepayid", prepay_id);
		results.put("noncestr", noncestr);
		results.put("timestamp", timestamp);
		results.put("sign", packageSign);

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
		String wxReturn = null;
		
		String appId = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxAppID", delegator);
		String wxPartnerid = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.wxPartnerid", delegator);
		String appKey = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.key", delegator);
		getApiConfig(appId, wxPartnerid, appKey);
		Map<String, String> map = WxPayApi.appPayNotify(request, appKey);
		
		try {
			if (map.get("return_code").toString().equalsIgnoreCase("SUCCESS")) {
				// 支付成功
				if ("SUCCESS".equalsIgnoreCase((String) map.get("result_code"))) {
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
					GenericValue payment = delegator.findByPrimaryKey("Payment", UtilMisc.toMap("paymentId", paymentId));
					if ("PMNT_RECEIVED".equals(payment.getString("statusId"))) {
						GenericValue systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin",
								UtilMisc.toMap("userLoginId", "system"));
						Map<String, Object> rechargeCloudCardDepositOutMap = dispatcher.runSync("rechargeCloudCardDeposit", UtilMisc.toMap("userLogin", systemUserLogin, "cardId",  cardId, "receiptPaymentId", paymentId, "organizationPartyId", storeId));
						
						//如果平台收款成功，钱打给店家，否则退给用户
						if (!ServiceUtil.isSuccess(rechargeCloudCardDepositOutMap)) {
							// TODO 平台入账 不成功 发起退款
							String certPass = EntityUtilProperties.getPropertyValue("cloudcard", "weixin.certPass", delegator);
							String amount = String.valueOf(Double.parseDouble(map.get("cash_fee"))/100);
							SortedMap<String, String> orderMap = new TreeMap<>();
							orderMap.put("appid", appId);
							orderMap.put("mch_id", wxPartnerid);
							orderMap.put("nonce_str", StringUtils.getNonceStr(32));
							orderMap.put("out_trade_no", map.get("out_trade_no"));
							orderMap.put("out_refund_no", payment.getString("paymentId"));
							orderMap.put("total_fee", amount);
							orderMap.put("refund_fee", amount);
							orderMap.put("op_user_id", wxPartnerid);
														
							String packageSign = PaymentKit.createSign(orderMap, WxPayApiConfigKit.getWxPayApiConfig().getPaternerKey());
							orderMap.put("sign", packageSign);
							
							String msg = WxPayApi.orderRefund(orderMap, "../cloud-card/hot-deploy/cloudcard/cert/apiclient_cert.p12", certPass);
							Debug.logError(msg, module);
							
							//返给微信平台信息
							SortedMap<String, Object> sort = new TreeMap<String, Object>();
							sort.put("return_code", "SUCCESS");
							sort.put("return_msg", "OK");
							wxReturn = XMLUtil.toXml(sort);
						}else {
							// 查找店家支付宝账号和支付宝姓名
							String payeeAccount = null;
							String payeeRealName = null;
							Map<String, Object> aliPayMap = CloudCardHelper.getStoreAliPayInfo(delegator, storeId);
							if (UtilValidate.isNotEmpty(aliPayMap)) {
								payeeAccount = aliPayMap.get("payAccount").toString();
								payeeRealName = aliPayMap.get("payName").toString();
							}
							// 查找转账折扣率
							double discount = Double.valueOf(EntityUtilProperties.getPropertyValue("cloudcard", "transfer.discount", "1", delegator));
							// 计算转账金额
							double price = Double.parseDouble(map.get("total_fee").toString()) / 100;
							double amount = price * discount;

							// 立即将钱打给商家
							AlipayFundTransToaccountTransferModel model = new AlipayFundTransToaccountTransferModel();
							model.setOutBizNo(paymentId); // 生成订单号
							model.setPayeeAccount(payeeAccount); // 转账收款账户
							model.setAmount(String.format("%.2f", amount)); // 账户收款金额
							model.setPayeeRealName(payeeRealName); // 账户真实名称
							model.setPayerShowName("宁波区快微贝网络技术有限公司");
							model.setRemark("来自库胖卡的收益");
							model.setPayeeType("ALIPAY_LOGONID");
							
							String partner = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangAppID",delegator);
							String service_url = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.url","https://openapi.alipay.com/gateway.do",delegator);
							String publicKey = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangPublicKey",delegator);
							String rsaPrivate = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.rsa_kupang_transfer_private",delegator);
							String signType = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.signType", delegator);

							AliPayServices.getApiConfig(partner,publicKey,"utf-8",rsaPrivate,service_url,signType);
							boolean isSuccess = AliPayApi.transfer(model);
							AliPayApiConfigKit.removeThreadLocalApiConfig();

							// 如果转账成功,设置Payment状态为PMNT_CONFIRMED
							if (isSuccess) {
								Map<String, Object> setPaymentStatusOutMap;
								try {
									setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus", UtilMisc.toMap("userLogin", systemUserLogin, "locale", null, "paymentId", paymentId, "statusId", "PMNT_CONFIRMED"));
								} catch (GenericServiceException e1) {
									Debug.logError(e1, module);
								}
							} else {
								
							}
						}
						
					} else {
						// 支付失败
					}
				}

				SortedMap<String, Object> sort = new TreeMap<String, Object>();
				sort.put("return_code", "SUCCESS");
				sort.put("return_msg", "OK");
				wxReturn = XMLUtil.toXml(sort);
				
			} else {
				// 支付失败
				SortedMap<String, Object> sort = new TreeMap<String, Object>();
				sort.put("return_code", "FAIL");
				sort.put("return_msg", "签名失败");
				wxReturn = XMLUtil.toXml(sort);
			}

		} catch (Exception e) {
			Debug.logError(e.getMessage(), module);
		}
		
		//销毁apiClient
		AliPayApiConfigKit.removeThreadLocalApiConfig();

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
		
		Map<String, Object> results = ServiceUtil.returnSuccess();
		return results;
	}
}
