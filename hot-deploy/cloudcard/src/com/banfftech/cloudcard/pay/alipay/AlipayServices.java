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

import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.ServiceUtil;

import com.alibaba.fastjson.JSON;
import com.banfftech.cloudcard.pay.alipay.bean.AlipayNotification;
import com.banfftech.cloudcard.pay.alipay.util.AlipayNotify;
import com.banfftech.cloudcard.pay.alipay.util.RequestUtils;
import com.banfftech.cloudcard.pay.alipay.util.StringUtils;

public class AlipayServices {
	public static String PARTNER = "xxx";
	public static String SELLER = "xxxx";
	public static String RSA_PRIVATE = "xxx";

	
	/**
	 * 预支付订单
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> prepayOrder(DispatchContext dctx, Map<String, Object> context) {
		String orderInfo = getOrderInfo("充值", "充值", "0.01", getOutTradeNo());
		String sign = StringUtils.sign(orderInfo, RSA_PRIVATE);

		try {
			// 仅需对sign 做URL编码
			sign = URLEncoder.encode(sign, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		// 完整的符合支付宝参数规范的订单信息
		final String payInfo = orderInfo + "&sign=\"" + sign + "\"&" + getSignType();
		System.out.println(payInfo);
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

	public static void receiveNotify(HttpServletRequest request, HttpServletResponse response) {

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
	 */
	private static String getOrderInfo(String subject, String body, String price, String out_trade_no) {

		// 签约合作者身份ID
		String orderInfo = "partner=" + "\"" + PARTNER + "\"";

		// 签约卖家支付宝账号
		orderInfo += "&seller_id=" + "\"" + SELLER + "\"";

		// 商户网站唯一订单号
		orderInfo += "&out_trade_no=" + "\"" + out_trade_no + "\"";

		// 商品名称
		orderInfo += "&subject=" + "\"" + subject + "\"";

		// 商品详情
		orderInfo += "&body=" + "\"" + body + "\"";

		// 商品金额
		orderInfo += "&total_fee=" + "\"" + price + "\"";

		// 服务器异步通知页面路径
		orderInfo += "&notify_url=" + "\"" + "http://cloudcard.ngrok.joinclub.cn/cloudcard/control/wxOrderNotify"
				+ "\"";

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

		// 调用银行卡支付，需配置此参数，参与签名， 固定值 （需要签约《无线银行卡快捷支付》才能使用）
		// orderInfo += "&paymethod=\"expressGateway\"";

		return orderInfo;
	}

}