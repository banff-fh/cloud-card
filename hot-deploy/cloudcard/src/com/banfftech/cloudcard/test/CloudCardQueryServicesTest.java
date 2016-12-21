package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardHelper;

import javolution.util.FastList;
import javolution.util.FastMap;

/**
 * <p>一些云卡相关查询服务的测试</p>
 * <pre>
 * 注意，
 *   1、每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 *   2、父类中的test方法，在每个子类测试的时候都会被执行
 * </pre>
 * @author cy
 * @see CloudCardServicesTest
 */
public class CloudCardQueryServicesTest extends CloudCardServicesTest {


	public CloudCardQueryServicesTest(String name) {
		super(name);
	}

	/**
	 * 测试 根据条码查询商家信息
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testGetStoreInfoByQRcode() throws GenericServiceException, GenericEntityException{
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "system"), false);

		GenericValue partyIdentification = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", STORE_ID_1, "partyIdentificationTypeId", "STORE_QR_CODE"), false);
		assertEquals( STORE_1_QR_CODE, partyIdentification.getString("idValue"));

		GenericValue partyIdentification2 = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", STORE_ID_2, "partyIdentificationTypeId", "STORE_QR_CODE"), false);
		assertEquals( STORE_2_QR_CODE, partyIdentification2.getString("idValue"));

		partyIdentification = CloudCardHelper.getPartyGroupByQRcodeNoException(STORE_1_QR_CODE, delegator);
		assertEquals(STORE_ID_1, partyIdentification.getString("partyId"));

		partyIdentification = CloudCardHelper.getPartyGroupByQRcodeNoException(STORE_2_QR_CODE, delegator);
		assertEquals(STORE_ID_2, partyIdentification.getString("partyId"));


		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("qrCode", STORE_1_QR_CODE);
		Map<String, Object> resp = dispatcher.runSync("getStoreInfoByQRcode", ctx);

		assertTrue("Service 'getStoreInfoByQRcode' SHOULD result success", ServiceUtil.isSuccess(resp));

		String storeId = (String) resp.get("storeId");
		String storeName = (String) resp.get("storeName");
		assertEquals("storeId should be expectedStoreId", STORE_ID_1, storeId);
		assertEquals("storeName should be expectedStoreName", STORE_NAME_1, storeName);

		ctx.clear();
		ctx.put("userLogin", userLogin);
		ctx.put("qrCode", INVALID_STORE_QR_CODE);
		resp = dispatcher.runSync("getStoreInfoByQRcode", ctx);
		assertFalse("this call of service 'getStoreInfoByQRcode' input invalid qrCode,  SHOULD NOT result success", ServiceUtil.isSuccess(resp));

	}

	/**
	 * 测试用户卡列表
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testMyCloudCards()  throws GenericServiceException, GenericEntityException{

		/********************* 卡数据准备 ***************************/
		// 一店 生成3张新卡
		//String cardNameA = "商家一测试用户卡列表用";
		int countA = 3;

		//Map<String, Object> resp = callGenNewCloudCardCode(STORE_ID_1, cardNameA, countA);
		//assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该至少有3张
		List<GenericValue> cloudCardListA = queryCardForStoreFromDB(STORE_ID_1, null, "FNACT_CREATED");
		assertTrue("should has " + countA + " more cards here",  cloudCardListA.size() >= countA);
		cloudCardListA = cloudCardListA.subList(0, countA);

		// 全部调整成待激活的状态
		List<GenericValue> toBeStore = FastList.newInstance();
		for(GenericValue cc: cloudCardListA){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}

		// 二店 生成2张新卡
		int countB = 2;
//		String cardNameB = "商家二测试用户卡列表用";

//		resp = callGenNewCloudCardCode(STORE_ID_2, cardNameB, countB);
//		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该至少2张
		List<GenericValue> cloudCardListB = queryCardForStoreFromDB(STORE_ID_2, null, "FNACT_CREATED");
		assertTrue("should has " + countB + " more cards here", cloudCardListB.size()>= countB);
		cloudCardListB = cloudCardListB.subList(0, countB);

		// 全部调整成待激活的状态
		for(GenericValue cc: cloudCardListB){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}
		delegator.storeAll(toBeStore);

		String cardCodeA_1 = cloudCardListA.get(0).getString("finAccountCode");
		BigDecimal amountA_1 = new BigDecimal(200.00f).setScale(decimals, rounding);

		String cardCodeA_2 = cloudCardListA.get(1).getString("finAccountCode");
		BigDecimal amountA_2 = new BigDecimal(123.00f).setScale(decimals, rounding);

		String cardCodeA_3 = cloudCardListA.get(2).getString("finAccountCode");
		BigDecimal amountA_3 = new BigDecimal(155.12f).setScale(decimals, rounding);

		String cardCodeB_1 = cloudCardListB.get(0).getString("finAccountCode");
		BigDecimal amountB_1 = new BigDecimal(444.44f).setScale(decimals, rounding);

		String cardCodeB_2 = cloudCardListB.get(1).getString("finAccountCode");
		BigDecimal amountB_2 = new BigDecimal(600.01f).setScale(decimals, rounding);

		String user1_tele_number = "011011011011";
		String user2_tele_number = "022022022022";

		/********************* 卡数据准备结束 ***************************/

		// 测试1 给 用户一 开一张卡，然后查询卡列表
		Map<String, Object> resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_1, amountA_1, user1_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		String cardId = (String) resp.get("cardId"); //服务返回 卡Id
		String customerPartyId = (String) resp.get("customerPartyId"); //服务返回 客户Id

		List<GenericValue> cardList = queryCardForCustomerFromDB(customerPartyId, false);
		assertEquals("should has 1 cards here", 1, cardList.size());

		// 查询用户一应该只有一张卡
		resp = callMyCloudCards(user1_tele_number, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));

		Integer listSize = (Integer) resp.get("listSize");
		assertEquals("should has 1 cards here", 1, listSize.intValue()); 

		List<Map<String,Object>> cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		assertEquals("listSize should be equal with cloudCardList.size()", listSize.intValue(), cloudCardList.size());

		Map<String, Object> oneCard = cloudCardList.get(0);
		assertEquals("cardId should be equals", cardId, oneCard.get("cardId"));
		assertEquals("cardCode should be equals", cardCodeA_1, oneCard.get("cardCode"));
		assertEquals("cardBalance should be equals", amountA_1, oneCard.get("cardBalance"));
		assertEquals("distributorPartyId should be equals", STORE_ID_1, oneCard.get("distributorPartyId"));

		// 测试2 再给 用户一 连续开两张卡，再查询卡信息列表
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_2, amountA_2, user1_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_3, amountA_3, user1_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callMyCloudCards(user1_tele_number, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		listSize = (Integer) resp.get("listSize");
		assertEquals("should has 3 cards here", 3, listSize.intValue());

		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		BigDecimal total = ZERO;
		for(Map<String, Object> card: cloudCardList){
			total = total.add((BigDecimal)card.get("cardBalance"));
		}

		assertEquals("total cardBalance should be equals", 
				amountA_1.add(amountA_2).add(amountA_3).setScale(decimals, rounding), 
				total.setScale(decimals, rounding));

		// 测试3 给 用户二 连续开两张卡，再查询卡信息列表
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCodeB_1, amountB_1, user2_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCodeB_2, amountB_2, user2_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callMyCloudCards(user2_tele_number, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		listSize = (Integer) resp.get("listSize");
		assertEquals("should has 2 cards here", 2, listSize.intValue());

		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		total = ZERO;
		for(Map<String, Object> card: cloudCardList){
			total = total.add((BigDecimal)card.get("cardBalance"));
		}

		assertEquals("total cardBalance should be equals", 
				amountB_1.add(amountB_2).setScale(decimals, rounding), 
				total.setScale(decimals, rounding));

	}

	/**
	 * 测试 商家卖卡额度等信息
	 * @throws GenericEntityException
	 * @throws GenericServiceException
	 */
	public void testGetLimitAndPresellInfo() throws GenericEntityException, GenericServiceException{

		/************************ 准备卡数据 开始 ****************************/
		int countA = 3;
		List<GenericValue> cloudCardListA = queryCardForStoreFromDB(STORE_ID_1, null, "FNACT_CREATED");
		assertTrue("should has " + countA + " more cards here",  cloudCardListA.size() >= countA);
		cloudCardListA = cloudCardListA.subList(0, countA);

		// 全部调整成待激活的状态
		List<GenericValue> toBeStore = FastList.newInstance();
		for(GenericValue cc: cloudCardListA){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}
		delegator.storeAll(toBeStore);

		String cardCodeA_1 = cloudCardListA.get(0).getString("finAccountCode");
		BigDecimal amountA_1 = new BigDecimal(200.00f).setScale(decimals, rounding);

		String cardCodeA_2 = cloudCardListA.get(1).getString("finAccountCode");
		BigDecimal amountA_2 = new BigDecimal(123.00f).setScale(decimals, rounding);

		String cardCodeA_3 = cloudCardListA.get(2).getString("finAccountCode");
		BigDecimal amountA_3 = new BigDecimal(155.12f).setScale(decimals, rounding);

		String user1_tele_number = "aaaa11011011";
		String user2_tele_number = "abbb22022022";

		/************************ 准备卡数据 结束 ****************************/

		// 先查询一遍，作为基准
		Map<String, Object> resp = callGetLimitAndPresellInfo(STORE_1_USER, STORE_ID_1);
		assertTrue("Service 'getLimitAndPresellInfo' SHOULD result success", ServiceUtil.isSuccess(resp));

		BigDecimal presellAmount = (BigDecimal) resp.get("presellAmount"); //已卖出金额
		BigDecimal limitAmount = (BigDecimal) resp.get("limitAmount"); // 卖卡限额
		BigDecimal balance = (BigDecimal) resp.get("balance"); // 卖卡余额
		BigDecimal liabilities = (BigDecimal) resp.get("liabilities"); // 负债金额（卖出去还未消费的卡总额）
		// TODO
		//BigDecimal settlementAmount = (BigDecimal) resp.get("settlementAmount"); // 跨店消费待结算金额
		
		// 测试1、卖出一张卡 再查询
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_1, amountA_1, user1_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callGetLimitAndPresellInfo(STORE_1_USER, STORE_ID_1);
		assertTrue("Service 'getLimitAndPresellInfo' SHOULD result success", ServiceUtil.isSuccess(resp));

		BigDecimal newPresellAmount = (BigDecimal) resp.get("presellAmount");
		BigDecimal newLimitAmount = (BigDecimal) resp.get("limitAmount");
		BigDecimal newBalance = (BigDecimal) resp.get("balance");
		BigDecimal newLiabilities = (BigDecimal) resp.get("liabilities");
		
		assertEquals("presellAmount should be increased by the recharge amount:" + amountA_1.toPlainString(), 
				presellAmount.add(amountA_1).setScale(decimals, rounding), 
				newPresellAmount.setScale(decimals, rounding));

		assertEquals("limitAmount should never be changed", 
				limitAmount.setScale(decimals, rounding), 
				newLimitAmount.setScale(decimals, rounding));

		assertEquals("The balance should be reduced by the recharge amount:" + amountA_1.toPlainString(), 
				balance.subtract(amountA_1).setScale(decimals, rounding), 
				newBalance.setScale(decimals, rounding));

		assertEquals("The liabilities should be increased by the recharge amount:" + amountA_1.toPlainString(), 
				liabilities.add(amountA_1).setScale(decimals, rounding), 
				newLiabilities.setScale(decimals, rounding));
		
		

		// 测试2、 再向另一个用户卖出一张卡 再查询
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_2, amountA_2, user2_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		resp = callGetLimitAndPresellInfo(STORE_1_USER, STORE_ID_1);
		assertTrue("Service 'getLimitAndPresellInfo' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		presellAmount = newPresellAmount;
		limitAmount = newLimitAmount;
		balance = newBalance;
		liabilities = newLiabilities;
		 
		newPresellAmount = (BigDecimal) resp.get("presellAmount");
		newLimitAmount = (BigDecimal) resp.get("limitAmount");
		newBalance = (BigDecimal) resp.get("balance");
		newLiabilities = (BigDecimal) resp.get("liabilities");
		
		assertEquals("presellAmount should be increased by the recharge amount:" + amountA_2.toPlainString(), 
				presellAmount.add(amountA_2).setScale(decimals, rounding), 
				newPresellAmount.setScale(decimals, rounding));

		assertEquals("limitAmount should never be changed", 
				limitAmount.setScale(decimals, rounding), 
				newLimitAmount.setScale(decimals, rounding));

		assertEquals("The balance should be reduced by the recharge amount:" + amountA_2.toPlainString(), 
				balance.subtract(amountA_2).setScale(decimals, rounding), 
				newBalance.setScale(decimals, rounding));

		assertEquals("The liabilities should be increased by the recharge amount:" + amountA_2.toPlainString(), 
				liabilities.add(amountA_2).setScale(decimals, rounding), 
				newLiabilities.setScale(decimals, rounding));
		
		// 测试3、 TODO
		
	}

	/**
	 * 测试 根据二维码查询卡信息
	 * @throws GenericEntityException
	 * @throws GenericServiceException
	 */
	public void testGetCardInfoByCode() throws GenericEntityException, GenericServiceException{
		/************************ 准备卡数据 开始 ****************************/
		int count = 2;
		List<GenericValue> cloudCardList = queryCardForStoreFromDB(STORE_ID_2, null, "FNACT_CREATED");
		assertTrue("should has " + count + " more cards here",  cloudCardList.size() >= count);
		cloudCardList = cloudCardList.subList(0, count);

		// 全部调整成待激活的状态
		List<GenericValue> toBeStore = FastList.newInstance();
		for(GenericValue cc: cloudCardList){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}
		delegator.storeAll(toBeStore);

		String cardCode_1 = cloudCardList.get(0).getString("finAccountCode");
		BigDecimal amount_1 = new BigDecimal(200.00f).setScale(decimals, rounding);

		String cardCode_2 = cloudCardList.get(1).getString("finAccountCode");
		BigDecimal amount_2 = new BigDecimal(123.00f).setScale(decimals, rounding);

		String user_tele_number = "CCCC11011011";

		/************************ 准备卡数据 结束 ****************************/

		// 测试1、 还没卖出的卡，查询返回 isActivated = N
		Map<String, Object> resp = callGetCardInfoByCode(STORE_2_USER, STORE_ID_2, cardCode_1);
		assertTrue("Service 'getCardInfoByCode' SHOULD result success", ServiceUtil.isSuccess(resp));

		String isActivated = (String) resp.get("isActivated");
		assertEquals("The output param: isActivated should be 'N' ", "N", isActivated);

		// 测试2、 卖出一张卡 再查询
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCode_1, amount_1, user_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		String cardId = (String) resp.get("cardId");
		BigDecimal cardBalance = (BigDecimal) resp.get("cardBalance");
		String customerPartyId = (String) resp.get("customerPartyId");

		resp = callGetCardInfoByCode(STORE_2_USER, STORE_ID_2, cardCode_1);
		assertTrue("Service 'getCardInfoByCode' SHOULD result success", ServiceUtil.isSuccess(resp));

		isActivated = (String) resp.get("isActivated");
		assertEquals("The output param: isActivated should be 'Y' ", "Y", isActivated);
		
		assertEquals("The output param: cardId should be equal ", cardId, resp.get("cardId"));
		assertEquals("The output param: cardBalance should be equal ", 
				cardBalance.setScale(decimals, rounding), 
				((BigDecimal)resp.get("cardBalance")).setScale(decimals, rounding));

		assertEquals("The output param: customerPartyId should be equal ", customerPartyId, resp.get("customerPartyId"));

		// 测试3、 再卖出另一张卡 再查询
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCode_2, amount_2, user_tele_number);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		cardId = (String) resp.get("cardId");
		cardBalance = (BigDecimal) resp.get("cardBalance");
		customerPartyId = (String) resp.get("customerPartyId");

		resp = callGetCardInfoByCode(STORE_2_USER, STORE_ID_2, cardCode_2);
		assertTrue("Service 'getCardInfoByCode' SHOULD result success", ServiceUtil.isSuccess(resp));

		isActivated = (String) resp.get("isActivated");
		assertEquals("The output param: isActivated should be 'Y' ", "Y", isActivated);

		assertEquals("The output param: cardId should be equal ", cardId, resp.get("cardId"));
		assertEquals("The output param: cardBalance should be equal ", 
				cardBalance.setScale(decimals, rounding), 
				((BigDecimal)resp.get("cardBalance")).setScale(decimals, rounding));

		assertEquals("The output param: customerPartyId should be equal ", customerPartyId, resp.get("customerPartyId"));

	}
	
	// TODO 交易流水的查询
}
