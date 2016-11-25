package com.banfftech.cloudcard.pay.alipay.config;


/* *
 *类名：AlipayConfig
 *功能：基础配置类
 *详细：设置帐户有关信息及返回路径
 *版本：3.3
 *日期：2012-08-10
 *说明：
 *以下代码只是为了方便商户测试而提供的样例代码，商户可以根据自己网站的需要，按照技术文档编写,并非一定要使用该代码。
 *该代码仅供学习和研究支付宝接口使用，只是提供一个参考。
	
 *提示：如何获取安全校验码和合作身份者ID
 *1.用您的签约支付宝账号登录支付宝网站(www.alipay.com)
 *2.点击“商家服务”(https://b.alipay.com/order/myOrder.htm)
 *3.点击“查询合作者身份(PID)”、“查询安全校验码(Key)”

 *安全校验码查看时，输入支付密码后，页面呈灰色的现象，怎么办？
 *解决方法：
 *1、检查浏览器配置，不让浏览器做弹框屏蔽设置
 *2、更换浏览器或电脑，重新登录查询。
 */

public class AlipayConfig {
	
	//↓↓↓↓↓↓↓↓↓↓请在这里配置您的基本信息↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
	// 合作身份者ID，以2088开头由16位纯数字组成的字符串
	public static String partner = "2016102000727643";
	// 商户的私钥
	public static String private_key = "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBALm78NHNIFMwmyNkubTR0imrb7Sh/eN7pFve+h+JfDsaiNl6FPzj/XhxiamcV52Eg1Ts1lA8amTmE32Emu9zju7yorfCCcmCMFeeI2xa3y5oOjObwmMvVufW5QSC7faaR1iUHLzJhpgIajb30BNiqSvHmxwCSQzqtvuB/oIxDBTHAgMBAAECgYBKCJL+XcBTyJ0eJ4kqfTRDbdVx79wur9scz61torTFLld8MIBrKUmgl8kitXHrFKXw1RK8Gsjh/R+puZC0f6BqCdCx8DReQpgUxWO/SS0kVmronaHMfi0s5fA18X7dkEs3OQPS9IteSQq0FJtd+RtJiWhNJ24vvhjWJVJgvNl+mQJBAPEXB5KaEEfE+0tPJvyW/wPmRUImiG4OtyWivutHmXkVS5aTPgLmFfb+fVklBbjUG/DS89LOPa8AxiC2Rq2jVysCQQDFOIKO9jidt6N+rZ0s2P3bt2EIHpUgsi8dELmp/LWNOIX58y0t39sJToK85+yLlmpQTQbg69awKDKEVLT/xqrVAkEA1qL0IPZ8TAj42IEtam9btjMJsezwRVtgfmc3pevmnL/yval06cvB/lVvby/guj5MacjFPgZTMDx2J6VfozLqZQJAW3oQpLr0G8OX5CQnKSwk44q1SQzWYuoDDFo7o+sBUtWK3xq6M/MHJ9PwtCpm/3/vI/v2WtFJLUAX3mVj5teBWQJBAN3U9Lq9wODIhSn0UNBK0iIsjDMgCQOXlhEdAoCuRWdsdbPTML/khrdWJtKPv9H+fcmWPCdMokH3GgAGYyXaiyU=";
	
	// 支付宝的公钥，无需修改该值
	public static String ali_public_key  = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIgHnOn7LLILlKETd6BFRJ0GqgS2Y3mn1wMQmyh9zEyWlz5p1zrahRahbXAfCfSqshSNfqOmAQzSHRVjCqjsAw1jyqrXaPdKBmr90DIpIxmIyKXv4GGAkPyJ/6FTFY99uhpiq0qadD/uSzQsefWo0aTvP/65zi3eof7TcZ32oWpwIDAQAB";

	//↑↑↑↑↑↑↑↑↑↑请在这里配置您的基本信息↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
	

	// 调试用，创建TXT日志文件夹路径
	public static String log_path = "D:\\";

	// 字符编码格式 目前支持 gbk 或 utf-8
	public static String input_charset = "utf-8";
	
	// 签名方式 不需修改
	public static String sign_type = "RSA";

}
