package com.banfftech.cloudcard.pay.alipay;

import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

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

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayFundTransToaccountTransferModel;
import com.alipay.api.domain.AlipayTradeAppPayModel;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApi;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApiConfig;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApiConfigKit;
import com.banfftech.cloudcard.pay.tenpay.api.WxPayApiConfigKit;
import com.banfftech.cloudcard.pay.util.StringUtils;

import javolution.util.FastMap;
import net.sf.json.JSONObject;

public class AliPayServices {

	public static final String module = AliPayServices.class.getName();
	
	public static void getApiConfig(String app_id,String alipay_public_key,String charset,String private_key,String service_url,String sign_type) {
		AliPayApiConfig aliPayApiConfig = AliPayApiConfig.New()
		.setAppId(app_id)
		.setAlipayPublicKey(alipay_public_key)
		.setCharset(charset)
		.setPrivateKey(private_key)
		.setServiceUrl(service_url)
		.setSignType(sign_type)
		.build();
		AliPayApiConfigKit.setThreadLocalAliPayApiConfig(aliPayApiConfig);
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

		String subject = (String) context.get("subject");
		String body = (String) context.get("body");
		String totalFee = (String) context.get("totalFee");
		String partner = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.partner", delegator);
		String service_url = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.url", delegator);
		String publicKey = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.publicKey", delegator);
		String rsaPrivate = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.rsa_private",delegator);
		String notifyUrl = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.notifyUrl", delegator);
		String signType = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.signType", delegator);
		String receiptPaymentId = (String) context.get("receiptPaymentId");
		String storeId = (String) context.get("storeId");
		String cardId = (String) context.get("cardId");

		getApiConfig(partner,publicKey,"utf-8",rsaPrivate,service_url,signType);
		AliPayApiConfigKit.removeThreadLocalApiConfig();

		AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
		model.setBody(body);
		model.setSubject(subject);
		model.setOutTradeNo(receiptPaymentId);
		model.setTimeoutExpress("30m");
		model.setTotalAmount(totalFee);
		model.setPassbackParams(receiptPaymentId  + "," + cardId + "," + storeId );
		
		String payInfo = null;
		try {
			payInfo = AliPayApi.startAppPayStr(model, notifyUrl);
		} catch (AlipayApiException e) {
			Debug.logError(e, module);
		}
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("payInfo", payInfo);
		return results;

	}

	/**
	 * 异步接受支付宝支付结果 支付宝服务器调用
	 *
	 * @param request
	 * @param response
	 */

	public static void aliPayNotify(HttpServletRequest request, HttpServletResponse response) {
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		Delegator delegator = dispatcher.getDelegator();
		
		String publicKey = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.publicKey", delegator);
		String signType = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.signType", delegator);
	
		Map<String,String> noticeMap = AliPayApi.appPayNotify(request, publicKey, "utf-8", signType);
		try {
			String resultResponse = "success";
			PrintWriter printWriter = null;
			try {
				printWriter = response.getWriter();
				// do business
				if (UtilValidate.isNotEmpty(noticeMap)) {
					resultResponse = "success";
					String cbstr = noticeMap.get("extraCommonParam");
					String[] arr = cbstr.split(",");
					String paymentId = "";
					String cardId = "";
					String storeId = "";
					if (arr.length >= 3) {
						paymentId = arr[0];
						cardId = arr[1];
						storeId = arr[2];
					}

					GenericValue payment = delegator.findByPrimaryKey("Payment",
							UtilMisc.toMap("paymentId", paymentId));
					if ("PMNT_RECEIVED".equals(payment.getString("statusId"))) {
						resultResponse = "success";
						String tradeStatus = noticeMap.get("tradeStatus");
						if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
							GenericValue systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin",
									UtilMisc.toMap("userLoginId", "system"));
							Map<String, Object> rechargeCloudCardDepositOutMap = dispatcher.runSync(
									"rechargeCloudCardDeposit", UtilMisc.toMap("userLogin", systemUserLogin, "cardId",
											cardId, "receiptPaymentId", paymentId, "organizationPartyId", storeId));

							//判断平台是否入账成功
							if (!ServiceUtil.isSuccess(rechargeCloudCardDepositOutMap)) {
								// TODO 平台入账 不成功 发起退款
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
								double price = Double.valueOf(noticeMap.get("price"));
								double amount = price * discount;
								
								// 立即将钱打给商家
								AlipayFundTransToaccountTransferModel model =  new AlipayFundTransToaccountTransferModel();
								model.setOutBizNo(paymentId); //生成订单号
								model.setPayeeAccount(payeeAccount); //转账收款账户
								model.setAmount(String.format("%.2f", amount)); //账户收款金额
								model.setPayeeRealName(payeeRealName); //账户真实名称
								model.setPayerShowName("宁波区快微贝网络技术有限公司");
								model.setRemark("来自库胖卡" + noticeMap.get("body") + "的收益");
								
								AliPayApiConfig aliPayApiConfig = AliPayApiConfig.New();
								aliPayApiConfig.setServiceUrl(EntityUtilProperties.getPropertyValue("cloudcard","aliPay.url","https://openapi.alipay.com/gateway.do",delegator));
								aliPayApiConfig.setAppId(EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangAppID",delegator));
								aliPayApiConfig.setCharset("utf-8");
								aliPayApiConfig.setAlipayPublicKey(EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangPublicKey",delegator));
								aliPayApiConfig.setPrivateKey(EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.rsa_kupang_transfer_private",delegator));
								aliPayApiConfig.setSignType(EntityUtilProperties.getPropertyValue("cloudcard","aliPay.signType","RSA",delegator));
								aliPayApiConfig.setFormat("json");
								
								AliPayApiConfigKit.setThreadLocalAliPayApiConfig(aliPayApiConfig);
								boolean isSuccess = AliPayApi.transfer(model);
								AliPayApiConfigKit.removeThreadLocalApiConfig();
								
								// 如果转账成功,设置Payment状态为PMNT_CONFIRMED
								if (isSuccess) {
									Map<String, Object> setPaymentStatusOutMap;
									try {
										setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus",
												UtilMisc.toMap("userLogin", systemUserLogin, "locale", null, "paymentId",
														paymentId, "statusId", "PMNT_CONFIRMED"));
									} catch (GenericServiceException e1) {
										Debug.logError(e1, module);
									}
								} else {

								}
							}
						} else {

						}
					}
				}
				// fail due to verification error
				else {
					resultResponse = "fail";
				}

			} catch (Exception e) {
				resultResponse = "fail";
				printWriter.close();
			}

			if (printWriter != null) {
				printWriter.print(resultResponse);
			}

		} catch (Exception e1) {
			e1.printStackTrace();
		}
	}

	public static Map<String, Object> orderPayQuery(Delegator delegator, Map<String, Object> context) {
		String outTradeNo = (String) context.get("outTradeNo");
		String tradeNo = (String) context.get("transactionId");
		String qRsa_private = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.qRsa_private",
				delegator);
		String qRsa_public = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.qRsa_public",
				delegator);
		String qRsa_AppId = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.qRsa_appId",
				delegator);

		AlipayClient alipayClient = new DefaultAlipayClient("https://openapi.alipay.com/gateway.do", qRsa_AppId,
				qRsa_private, "json", "GBK", qRsa_public, "RSA");
		AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();

		// request.setBizContent("{" + "\"out_trade_no\":\""+outTradeNo.trim()+"\"}");

		// 判断商家交易流水是否为空，为空给空空字符串
		if (UtilValidate.isEmpty(outTradeNo)) {
			outTradeNo = "";
		}
		// 判断第三方流水是否为空，为空给空空字符串
		if (UtilValidate.isEmpty(tradeNo)) {
			tradeNo = "";
		}
		// 根据商户订单流水和支付宝流水查询
		request.setBizContent(
				"{" + "\"out_trade_no\":\"" + outTradeNo + "\"," + "\"trade_no\":\"" + tradeNo + "\"" + "  }");
		AlipayTradeQueryResponse response = null;
		try {
			response = alipayClient.execute(request);
		} catch (AlipayApiException e) {
			e.printStackTrace();
		}
		Map<String, Object> orderPayMap = FastMap.newInstance();
		if (response.isSuccess()) {
			orderPayMap = ServiceUtil.returnSuccess();
			JSONObject jsonObject = JSONObject.fromObject(response.getBody())
					.getJSONObject("alipay_trade_query_response");
			orderPayMap.put("cashFee", Double.parseDouble(jsonObject.get("total_amount").toString()));
			orderPayMap.put("returnCode", jsonObject.get("code"));
			orderPayMap.put("returnMsg", jsonObject.get("msg"));
			orderPayMap.put("outTradeNo", jsonObject.get("out_trade_no"));
			orderPayMap.put("tradeNo", jsonObject.get("trade_no"));
			orderPayMap.put("timeEnd", jsonObject.get("send_pay_date"));
			orderPayMap.put("tradeState", jsonObject.get("trade_status"));
		} else {
			orderPayMap = ServiceUtil.returnSuccess();
			JSONObject jsonObject = JSONObject.fromObject(response.getBody())
					.getJSONObject("alipay_trade_query_response");
			orderPayMap.put("tradeState", response.getSubMsg());
		}

		return orderPayMap;
	}

	/**
	 * 支付宝退款服务
	 *
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> refund(DispatchContext dctx, Map<String, Object> context) {
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}

}
