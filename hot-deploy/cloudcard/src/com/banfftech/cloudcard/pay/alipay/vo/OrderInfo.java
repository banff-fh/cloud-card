package com.banfftech.cloudcard.pay.alipay.vo;

public class OrderInfo {
	// 签约合作者身份ID
	private String partner;
	// 签约卖家支付宝账号
	private String sellerId;
	// 商户网站唯一订单号
	private String outTradeNo;
	// 商品名称
	private String subject;
	// 商品详情
	private String body;
	// 商品金额
	private String totalFee;
	// 服务器异步通知页面路径
	private String notifyUrl;
	// 服务接口名称， 固定值
	private String service = "mobile.securitypay.pay";
	// 支付类型， 固定值
	private String paymentType = "1";
	// 参数编码， 固定值
	private String inputCharset = "utf-8";
	// 设置未付款交易的超时时间
	// 默认30分钟，一旦超时，该笔交易就会自动被关闭。
	// 取值范围：1m～15d。
	// m-分钟，h-小时，d-天，1c-当天（无论交易何时创建，都在0点关闭）。
	// 该参数数值不接受小数点，如1.5h，可转换为90m。
	private String itBPay = "30m";
	// 支付宝处理完请求后，当前页面跳转到商户指定页面的路径，可空
	private String passbackParams;
	// 回调返回参数
	private String returnUrl;

	public String getPartner() {
		return partner;
	}

	public void setPartner(String partner) {
		this.partner = partner;
	}

	public String getSellerId() {
		return sellerId;
	}

	public void setSellerId(String sellerId) {
		this.sellerId = sellerId;
	}

	public String getOutTradeNo() {
		return outTradeNo;
	}

	public void setOutTradeNo(String outTradeNo) {
		this.outTradeNo = outTradeNo;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	public String getTotalFee() {
		return totalFee;
	}

	public void setTotalFee(String totalFee) {
		this.totalFee = totalFee;
	}

	public String getNotifyUrl() {
		return notifyUrl;
	}

	public void setNotifyUrl(String notifyUrl) {
		this.notifyUrl = notifyUrl;
	}

	public String getService() {
		return service;
	}

	public void setService(String service) {
		this.service = service;
	}

	public String getPaymentType() {
		return paymentType;
	}

	public void setPaymentType(String paymentType) {
		this.paymentType = paymentType;
	}

	public String getInputCharset() {
		return inputCharset;
	}

	public void setInputCharset(String inputCharset) {
		this.inputCharset = inputCharset;
	}

	public String getItBPay() {
		return itBPay;
	}

	public void setItBPay(String itBPay) {
		this.itBPay = itBPay;
	}

	public String getPassbackParams() {
		return passbackParams;
	}

	public void setPassbackParams(String passbackParams) {
		this.passbackParams = passbackParams;
	}

	public String getReturnUrl() {
		return returnUrl;
	}

	public void setReturnUrl(String returnUrl) {
		this.returnUrl = returnUrl;
	}

	@Override
	public String toString() {
		// 签约合作者身份ID
		String orderInfo = "partner=" + "\"" + getPartner() + "\"";

		// 签约卖家支付宝账号
		orderInfo += "&seller_id=" + "\"" + getSellerId() + "\"";

		// 商户网站唯一订单号
		orderInfo += "&out_trade_no=" + "\"" + getOutTradeNo() + "\"";

		// 商品名称
		orderInfo += "&subject=" + "\"" + getSubject() + "\"";

		// 商品详情
		orderInfo += "&body=" + "\"" + getBody() + "\"";

		// 商品金额
		orderInfo += "&total_fee=" + "\"" + getTotalFee() + "\"";

		// 服务器异步通知页面路径
		orderInfo += "&notify_url=" + "\"" + getNotifyUrl() + "\"";

		// 服务接口名称， 固定值
		orderInfo += "&service=\"" + service + "\"";

		// 支付类型， 固定值
		orderInfo += "&payment_type=\"" + paymentType + "\"";

		// 参数编码， 固定值
		orderInfo += "&_input_charset=\"" + inputCharset + "\"";

		// 设置未付款交易的超时时间
		// 默认30分钟，一旦超时，该笔交易就会自动被关闭。
		// 取值范围：1m～15d。
		// m-分钟，h-小时，d-天，1c-当天（无论交易何时创建，都在0点关闭）。
		// 该参数数值不接受小数点，如1.5h，可转换为90m。
		orderInfo += "&it_b_pay=\"" + itBPay + "\"";

		// extern_token为经过快登授权获取到的alipay_open_id,带上此参数用户将使用授权的账户进行支付
		// orderInfo += "&extern_token=" + "\"" + extern_token + "\"";

		// 支付宝处理完请求后，当前页面跳转到商户指定页面的路径，可空
		orderInfo += "&return_url=\"m.alipay.com\"";

		// 回调返回
		orderInfo += "&passback_params=" + "\"" + getPassbackParams() + "\"";
		return orderInfo;

	}

}
