package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

/**
 * <p>测试 卡支付相关 接口</p>
 * <pre>
 * 注意，
 *   1、每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 *   2、父类中的test方法，在每个子类测试的时候都会被执行
 * </pre>
 * @author cy
 * @see CloudCardServicesTest
 */
public class CardWithdrawTest extends CloudCardServicesTest {

	public CardWithdrawTest(String name) {
		super(name);
	}

	/**
	 * 测试 商家扫码收款
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testCloudCardWithdraw() throws GenericServiceException, GenericEntityException{

		// 获取用户卡
		Map<String, Object> resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));

		Integer listSize = (Integer) resp.get("listSize");
		assertTrue("should has 1 more cards here", listSize >=1);
		
		List<Map<String,Object>> cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));

		Map<String,Object> oneCard = null;
		BigDecimal paymentAmount = new BigDecimal(10.00f);

		for(Map<String,Object> cc: cloudCardList){
			String tmp_distributorPartyId = (String) cc.get("distributorPartyId");
			BigDecimal tmp_cardBalance = (BigDecimal) cc.get("cardBalance");
			if(STORE_ID_1.equals(tmp_distributorPartyId) &&
					tmp_cardBalance.subtract(paymentAmount).compareTo(ZERO)>=0){
				// 找到一张可用于本次测试的卡，正常情况一定能找到这张卡的
				oneCard = cc;
			}
		}
		assertNotNull("the card SHOULD NOT be null", oneCard);

		String cardId = (String) oneCard.get("cardId");
		String cardCode = (String) oneCard.get("cardCode");
		String ownerPartyId = (String) oneCard.get("ownerPartyId");
		BigDecimal cardBalance = (BigDecimal) oneCard.get("cardBalance");
		

		// 测试1 登录用户不是商家，使用商家的收款接口，不应该成功
		resp = callCloudCardWithdraw(CUSTOMER_1_TEL, STORE_ID_1, cardCode, paymentAmount);
		assertFalse("Service 'cloudCardWithdraw' SHOULD NOT result success while the user is not the manager of a store", ServiceUtil.isSuccess(resp));

		// 测试2 收款金额大于卡余额，不应该成功
		resp = callCloudCardWithdraw(STORE_1_USER,  STORE_ID_1, cardCode, cardBalance.add(paymentAmount));
		assertFalse("Service 'cloudCardWithdraw' SHOULD NOT result success when the card balance is NOT enough", ServiceUtil.isSuccess(resp));

		// 测试3 收款金额为0，不应该成功
		resp = callCloudCardWithdraw(STORE_1_USER,  STORE_ID_1, cardCode, ZERO);
		assertFalse("Service 'cloudCardWithdraw' SHOULD NOT result success when the payment amount is ZERO", ServiceUtil.isSuccess(resp));

		// 测试4 收款金额为负数，不应该成功
		resp = callCloudCardWithdraw(STORE_1_USER,  STORE_ID_1, cardCode, paymentAmount.negate());
		assertFalse("Service 'cloudCardWithdraw' SHOULD NOT result success when the payment amount is less than zero", ServiceUtil.isSuccess(resp));

		// 测试5 正常收款
		resp = callCloudCardWithdraw(STORE_1_USER,  STORE_ID_1, cardCode, paymentAmount);
		assertTrue("Service 'cloudCardWithdraw' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		// 余额检验
		BigDecimal new_cardBalance = (BigDecimal) resp.get("cardBalance");
		assertEquals("The card balance should be reduced by the payment amount:" + paymentAmount.toPlainString(), 
				cardBalance.subtract(paymentAmount).setScale(decimals, rounding), 
				new_cardBalance.setScale(decimals, rounding));
		
		// 通过查询用户卡列表信息，来检验余额
		resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		listSize = (Integer) resp.get("listSize");
		assertTrue("should has 1 more cards here", listSize >=1);
		
		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));

		Map<String,Object> theCard = null;
		for(Map<String,Object> cc: cloudCardList){
			String tmp_cardId = (String) cc.get("cardId");
			if(tmp_cardId.equals(cardId) ){
				theCard = cc;
				break;
			}
		}
		assertNotNull("theCard SHOULD NOT be null", theCard);
		BigDecimal query_cardBalance = (BigDecimal) theCard.get("cardBalance");
		assertEquals("The card balance of service 'cloudCardWithdraw' should be equal with The card balance of service 'myCloudCards'", 
				new_cardBalance.setScale(decimals, rounding), 
				query_cardBalance.setScale(decimals, rounding));

	}

}
