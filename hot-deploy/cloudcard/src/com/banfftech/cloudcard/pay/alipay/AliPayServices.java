package com.banfftech.cloudcard.pay.alipay;

import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

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
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApi;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApiConfig;
import com.banfftech.cloudcard.pay.alipay.api.AliPayApiConfigKit;
import com.banfftech.cloudcard.pay.alipay.util.SignUtils;

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
		String seller = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.seller", delegator);
		String rsaPrivate = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.rsa_private",delegator);
		String notifyUrl = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.notifyUrl", delegator);
		String signType = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.signType", delegator);
		String receiptPaymentId = (String) context.get("receiptPaymentId");
		String storeId = (String) context.get("storeId");
		String cardId = (String) context.get("cardId");

		String orderInfo = getOrderInfo(partner, seller, subject, body, totalFee, receiptPaymentId, notifyUrl,receiptPaymentId,cardId, storeId);
		String sign = SignUtils.sign(orderInfo, rsaPrivate, signType);

		try {
			// 仅需对sign 做URL编码
			sign = URLEncoder.encode(sign, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		// 完整的符合支付宝参数规范的订单信息
		final String payInfo = orderInfo + "&sign=\"" + sign + "\"&" + getSignType();
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
		String service_url = EntityUtilProperties.getPropertyValue("cloudcard", "aliPay.url", delegator);
		
		Map<String,String> noticeMap = AliPayApi.appPayNotify(request, publicKey, "utf-8", signType);
		try {
			String resultResponse = "success";
			PrintWriter printWriter = null;
			try {
				printWriter = response.getWriter();
				if (UtilValidate.isNotEmpty(noticeMap)) {
					resultResponse = "success";
					String cbstr = noticeMap.get("extra_common_param");
					String[] arr = cbstr.split(",");
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
						resultResponse = "success";
						String tradeStatus = noticeMap.get("trade_status");
						if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
							
							String kupangPartner = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangAppID",delegator);
							String kuPangPublicKey = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangPublicKey",delegator);
							String kupangRsaPrivate = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.rsa_kupang_transfer_private",delegator);
							
							GenericValue systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
							Map<String, Object> rechargeCloudCardDepositOutMap = dispatcher.runSync("rechargeCloudCardDeposit", UtilMisc.toMap("userLogin", systemUserLogin, "cardId",cardId, "receiptPaymentId", paymentId, "organizationPartyId", storeId));
							//判断平台是否入账成功
							if (!ServiceUtil.isSuccess(rechargeCloudCardDepositOutMap)) {
								// TODO 平台入账 不成功 发起退款
								AlipayTradeRefundModel model = new AlipayTradeRefundModel();
								model.setTradeNo(noticeMap.get("trade_no"));
								model.setRefundAmount(noticeMap.get("total_fee"));
								model.setRefundReason("交易失败退款");
								
								getApiConfig(kupangPartner,kuPangPublicKey,"utf-8",kupangRsaPrivate,service_url,signType);
								String resultStr = AliPayApi.tradeRefund(model);
								AliPayApiConfigKit.removeThreadLocalApiConfig();
								Debug.logError(resultStr, module);
								
								resultResponse = "success";
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
								model.setPayeeType("ALIPAY_LOGONID");
								
								getApiConfig(kupangPartner,kuPangPublicKey,"utf-8",kupangRsaPrivate,service_url,signType);
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

	
	/**
	 * get the sign type we use. 获取签名方式
	 *
	 */
	private static String getSignType() {
		return "sign_type=\"RSA\"";
	}
	
	/**
	 * create the order info. 创建订单信息 由服务器生成
	 * @param storeId
	 */
	private static String getOrderInfo(String partner, String seller, String subject, String body, String price,
			String out_trade_no, String notifyUrl,String receiptPaymentId,String cardId, String storeId) {

		// 签约合作者身份ID
		String orderInfo = "partner=" + "\"" + partner + "\"";

		// 签约卖家支付宝账号
		orderInfo += "&seller_id=" + "\"" + seller + "\"";

		// 商户网站唯一订单号
		orderInfo += "&out_trade_no=" + "\"" + out_trade_no + "\"";

		// 商品名称
		orderInfo += "&subject=" + "\"" + subject + "\"";

		// 商品详情
		orderInfo += "&body=" + "\"" + body + "\"";

		// 商品金额
		orderInfo += "&total_fee=" + "\"" + price + "\"";

		// 服务器异步通知页面路径
		orderInfo += "&notify_url=" + "\"" + notifyUrl + "\"";

		// 服务接口名称， 固定值
		orderInfo += "&service=\"mobile.securitypay.pay\"";

		// 支付类型， 固定值
		orderInfo += "&payment_type=\"1\"";

		// 参数编码， 固定值
		orderInfo += "&_input_charset=\"utf-8\"";

		// 设置未付款交易的超时时间
		// 默认30分钟，一旦超时，该笔交易就会自动被关闭。
		// 取值范围：1m～15d。
		// m-分钟，h-小时，d-天，1c-当天（无论交易何时创建，都在0点关闭）。
		// 该参数数值不接受小数点，如1.5h，可转换为90m。
		orderInfo += "&it_b_pay=\"30m\"";

		// extern_token为经过快登授权获取到的alipay_open_id,带上此参数用户将使用授权的账户进行支付
		// orderInfo += "&extern_token=" + "\"" + extern_token + "\"";

		// 支付宝处理完请求后，当前页面跳转到商户指定页面的路径，可空
		orderInfo += "&return_url=\"m.alipay.com\"";

		//回调返回
        orderInfo += "&passback_params=" + "\"" + receiptPaymentId + "," + cardId + "," + storeId + "\"";

		// 调用银行卡支付，需配置此参数，参与签名， 固定值 （需要签约《无线银行卡快捷支付》才能使用）
		// orderInfo += "&paymethod=\"expressGateway\"";

		return orderInfo;
	}

}
