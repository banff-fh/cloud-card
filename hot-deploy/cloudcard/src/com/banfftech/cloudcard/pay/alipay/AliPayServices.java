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
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.alibaba.fastjson.JSON;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.pay.alipay.bean.AlipayNotification;
import com.banfftech.cloudcard.pay.alipay.util.AlipayNotify;
import com.banfftech.cloudcard.pay.alipay.util.RequestUtils;
import com.banfftech.cloudcard.pay.alipay.util.StringUtils;

import javolution.util.FastMap;
import net.sf.json.JSONObject;

public class AliPayServices {

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
		
		String orderInfo = getOrderInfo(partner, seller, subject, body, totalFee, getOutTradeNo(), notifyUrl,receiptPaymentId,cardId, storeId);
		String sign = StringUtils.sign(orderInfo, rsaPrivate, signType);

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
	    Delegator delegator =  dispatcher.getDelegator();

		Map<String, String> underScoreKeyMap = RequestUtils.getStringParams(request);
		Map<String, String> camelCaseKeyMap = RequestUtils.convertKeyToCamelCase(underScoreKeyMap);
		// 首先验证调用是否来自支付宝
		boolean verifyResult = AlipayNotify.verify(request, underScoreKeyMap);

		try {
			String jsonString = JSON.toJSONString(camelCaseKeyMap);
			AlipayNotification notice = JSON.parseObject(jsonString, AlipayNotification.class);
			notice.setVerifyResult(verifyResult);

			String resultResponse = "success";
			PrintWriter printWriter = null;
			try {
				printWriter = response.getWriter();
				// do business
                if (verifyResult) {
                    resultResponse = "success";
                    String cbstr = notice.getExtraCommonParam();
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
                    if("PMNT_RECEIVED".equals(payment.getString("statusId"))){
                        resultResponse = "success";
                        String tradeStatus = notice.getTradeStatus();
                        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                            GenericValue systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
                            Map<String, Object> rechargeCloudCardDepositOutMap = dispatcher.runSync("rechargeCloudCardDeposit", UtilMisc.toMap("userLogin",
                                    systemUserLogin, "cardId", cardId, "receiptPaymentId", paymentId, "organizationPartyId", storeId));

                            if (!ServiceUtil.isSuccess(rechargeCloudCardDepositOutMap)) {
                                // TODO 平台入账 不成功 发起退款
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
		String qRsa_private = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.qRsa_private",delegator);
		String qRsa_public= EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.qRsa_public",delegator);
		String qRsa_AppId = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.qRsa_appId",delegator);
		
		AlipayClient alipayClient = new DefaultAlipayClient("https://openapi.alipay.com/gateway.do", qRsa_AppId, qRsa_private, "json", "GBK", qRsa_public, "RSA");
		AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
		
		//request.setBizContent("{" + "\"out_trade_no\":\""+outTradeNo.trim()+"\"}");
		
		// 判断商家交易流水是否为空，为空给空空字符串
		if (UtilValidate.isEmpty(outTradeNo)) {
			outTradeNo = "";
		}
		//判断第三方流水是否为空，为空给空空字符串
		if(UtilValidate.isEmpty(tradeNo)){
			tradeNo="";
		}
		//根据商户订单流水和支付宝流水查询
		request.setBizContent("{" + "\"out_trade_no\":\""+outTradeNo+"\"," + "\"trade_no\":\""+tradeNo+"\"" + "  }");
		AlipayTradeQueryResponse response = null;
		try {
			response = alipayClient.execute(request);
		} catch (AlipayApiException e) {
			e.printStackTrace();
		}
		Map<String, Object> orderPayMap = FastMap.newInstance();
		if (response.isSuccess()) {
			orderPayMap = ServiceUtil.returnSuccess();
			JSONObject jsonObject = JSONObject.fromObject(response.getBody()).getJSONObject("alipay_trade_query_response");
			orderPayMap.put("cashFee", Double.parseDouble(jsonObject.get("total_amount").toString()));
			orderPayMap.put("returnCode", jsonObject.get("code"));
			orderPayMap.put("returnMsg", jsonObject.get("msg"));
			orderPayMap.put("outTradeNo", jsonObject.get("out_trade_no"));
			orderPayMap.put("tradeNo", jsonObject.get("trade_no"));
			orderPayMap.put("timeEnd", jsonObject.get("send_pay_date"));
			orderPayMap.put("tradeState", jsonObject.get("trade_status"));
		} else {
			orderPayMap = ServiceUtil.returnSuccess();
			JSONObject jsonObject = JSONObject.fromObject(response.getBody()).getJSONObject("alipay_trade_query_response");
			orderPayMap.put("tradeState", response.getSubMsg());
		}
		
		return orderPayMap;
	}
	
	
	/**
	 * get the out_trade_no for an order. 获取外部订单号
	 * 
	 */
	public static String getOutTradeNo() {
		SimpleDateFormat format = new SimpleDateFormat("MMddHHmmss", Locale.getDefault());
		Date date = new Date();
		String key = format.format(date);

		Random r = new Random();
		key = key + r.nextInt();
		key = key.substring(0, 15);
		return key;
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
