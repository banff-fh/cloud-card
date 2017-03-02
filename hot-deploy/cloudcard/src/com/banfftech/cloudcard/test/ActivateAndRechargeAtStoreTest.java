package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastList;

/**
 * <p>测试商家端  扫描卡 进行激活 或(和) 充值</p>
 * <pre>
 * 注意，
 *   1、每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 *   2、父类中的test方法，在每个子类测试的时候都会被执行
 * </pre>
 * @author cy
 * @see CloudCardServicesTest
 */
public class ActivateAndRechargeAtStoreTest extends CloudCardServicesTest {

	public ActivateAndRechargeAtStoreTest(String name) {
		super(name);
	}

	/**
	 * 测试开卡/充值
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testActivateCloudCardAndRecharge() throws GenericServiceException, GenericEntityException{

		/*
		// 生成2张新卡
			Map<String, Object> resp = callGenNewCloudCardCode(STORE_ID_1, "测试充值用", 2);
			assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));
		 */
		// 获取商家的卡
		List<GenericValue> cloudCardList = queryCardForStoreFromDB(STORE_ID_1, null, "FNACT_CREATED");
		assertTrue("should has 2 more cards here",  cloudCardList.size() >=2);
		
		cloudCardList = cloudCardList.subList(0, 2);
		// 全部调整成待激活的状态
		List<GenericValue> toBeStore = FastList.newInstance();
		for(GenericValue cc: cloudCardList){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}
		delegator.storeAll(toBeStore);

		String cardCode1_1 = cloudCardList.get(0).getString("finAccountCode");
		String cardCode1_2 = cloudCardList.get(1).getString("finAccountCode");

		// 测试1 激活新卡同时充值200 服务应当调用成功
		BigDecimal amount = new BigDecimal(200.00f).setScale(decimals, rounding);
		Map<String, Object> resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, amount, CUSTOMER_1_TEL);

		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		String cardId = (String) resp.get("cardId"); //服务返回 卡Id
		String customerPartyId = (String) resp.get("customerPartyId"); //服务返回 客户Id

		assertTrue("cardId SHOULD NOT EMPTY", UtilValidate.isNotEmpty(cardId)); 
		assertTrue("customerPartyId SHOULD NOT EMPTY", UtilValidate.isNotEmpty(customerPartyId)); 
		assertEquals("amount SHOULD Equals the amount of recharge", amount, resp.get("amount")); 

		BigDecimal cardBalance = (BigDecimal) resp.get("cardBalance");
		assertTrue("cardBalance SHOULD NOT EMPTY", UtilValidate.isNotEmpty(cardBalance)); 
		assertEquals("cardBalance SHOULD Equals the amount of recharge", amount, cardBalance); 


		// 测试2 同一张卡再次充值500 服务应当调用成功
		BigDecimal amount2 = new BigDecimal(500.00f);
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, amount2, null);

		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		String cardId2 = (String) resp.get("cardId");
		String customerPartyId2 = (String) resp.get("customerPartyId");

		assertTrue("cardId SHOULD NOT EMPTY", UtilValidate.isNotEmpty(cardId2)); 
		assertTrue("customerPartyId SHOULD NOT EMPTY", UtilValidate.isNotEmpty(customerPartyId2)); 

		// cardId 和 customerPartyId 应该和开卡的时候是相同的
		assertEquals("cardId SHOULD Be the same cardId of last recharge", cardId, cardId2); 
		assertEquals("customerPartyId SHOULD Be the same customerPartyId of last recharge", customerPartyId, customerPartyId2); 

		assertEquals("amount SHOULD Equals the amount of recharge", amount2, resp.get("amount")); 

		BigDecimal newCardBalance = (BigDecimal) resp.get("cardBalance");
		assertTrue("cardBalance SHOULD NOT EMPTY", UtilValidate.isNotEmpty(newCardBalance)); 

		assertEquals("cardBalance SHOULD Equals the amount of recharge add the last cardBalance", 
				amount2.add(cardBalance).setScale(decimals, rounding), 
				newCardBalance); 

		// 测试3 激活卡时候不传入teleNumber 服务应当返回失败
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_2, new BigDecimal(500.00f), null);
		assertFalse("Service 'activateCloudCardAndRecharge' activate a new card  SHOULD NOT result success when the teleNumber is Empty", ServiceUtil.isSuccess(resp));

		// 测试4 充值金额为0 服务应当返回失败
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, ZERO, null);
		assertFalse("Service 'activateCloudCardAndRecharge' SHOULD NOT result success when the amount is zero", ServiceUtil.isSuccess(resp));

		// 测试5 充值金额为小于0 服务应当返回失败
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, new BigDecimal(-200.00f), null);
		assertFalse("Service 'activateCloudCardAndRecharge' SHOULD NOT result success when the amount is LESS THAN zero", ServiceUtil.isSuccess(resp));

		// 测试6 错误的卡号 服务调用应该返回失败
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, "cardcode_NA_", new BigDecimal(300.00f), null);
		assertFalse("Service 'activateCloudCardAndRecharge' SHOULD NOT result success when the cardCode is invalid", ServiceUtil.isSuccess(resp));

		// 测试7 不匹配的商家充值(商家2 充值 商家1的卡) 服务调用应该返回失败
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCode1_1, new BigDecimal(300.00f), null);
		assertFalse("Service 'activateCloudCardAndRecharge' [recharge] SHOULD NOT result success when the store is not the distributor of card", ServiceUtil.isSuccess(resp));

		// 测试8 不匹配的商家激活卡(商家2 激活 商家1的卡) 服务调用应该返回失败
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCode1_2, new BigDecimal(300.00f), CUSTOMER_1_TEL);
		assertFalse("Service 'activateCloudCardAndRecharge' [activate]  SHOULD NOT result success when the store is not the distributor of card", ServiceUtil.isSuccess(resp));

		// 测试9 卖卡额度不够 服务调用应该返回失败
		resp =  callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, new BigDecimal(9999999999.00f), null);
		assertFalse("Service 'activateCloudCardAndRecharge' SHOULD NOT result success when the amount of card is greate than the limit of store", ServiceUtil.isSuccess(resp));

	}

}
