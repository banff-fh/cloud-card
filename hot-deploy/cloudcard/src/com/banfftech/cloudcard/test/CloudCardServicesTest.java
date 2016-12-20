package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilNumber;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.testtools.OFBizTestCase;

import com.banfftech.cloudcard.CloudCardHelper;

import javolution.util.FastList;
import javolution.util.FastMap;

/**
 * 注意，里面每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 */
public class CloudCardServicesTest extends OFBizTestCase {
	
	public static final String STORE_ID_1 = "testStore1";// 商家一的id
	public static final String STORE_1_USER = "15615615615";// 商家一的收银人员手机号

	public static final String STORE_ID_2 = "testStore2";// 商家二的id
	public static final String STORE_2_USER = "15715715715";// 商家二的收银人员手机号

	public static int decimals = UtilNumber.getBigDecimalScale("finaccount.decimals");
    public static int rounding = UtilNumber.getBigDecimalRoundingMode("finaccount.rounding");
	
	public CloudCardServicesTest(String name) {
		super(name);
	}

	/**
	 * 测试 生成云卡的服务
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testGenNewCloudCardCode() throws GenericServiceException, GenericEntityException{

		// 生成15张新卡
		int count = 15;
		Map<String, Object> resp = callGenNewCloudCardCode(STORE_ID_1, "测试商店一的卡15张", count);
		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该只有15张
		List<GenericValue> cloudCardList = queryCardForStoreFromDB(STORE_ID_1, "测试商店一的卡15张", "FNACT_CREATED");
		assertEquals("this service call for testStore1 should generate " + count + " cards", count, cloudCardList.size());

		// 为店家二生成100张新卡
		count = 100;
		resp = callGenNewCloudCardCode(STORE_ID_2, "测试商店二的卡", count);
		assertTrue("Service 'genNewCloudCardCode' for testStore2 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该有100张
		cloudCardList = queryCardForStoreFromDB(STORE_ID_2, "测试商店二的卡", "FNACT_CREATED");
		assertEquals("this service call for testStore2 should generate " + count + " cards", count, cloudCardList.size());

		// 不存在的店家服务应该调用失败
		/*count = 1;
		resp = callGenNewCloudCardCode("testStore_NA_", "不存在的店家不应该出现的卡", count);
		assertFalse("Service 'genNewCloudCardCode' for testStore_NA_  SHOULD NOT result success", ServiceUtil.isSuccess(resp));	
		 */

		// 再为商店二生成30张卡
		count = 30;
		resp = callGenNewCloudCardCode(STORE_ID_2, "测试商店二的卡", count);
		assertTrue("Service 'genNewCloudCardCode' for testStore2 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该有100张+30张 = 130张
		cloudCardList = queryCardForStoreFromDB(STORE_ID_2, "测试商店二的卡", "FNACT_CREATED");
		assertEquals("testStore2 should has 130 cards now!", 130, cloudCardList.size());

	}
	
	/**
	 * 测试 根据条码查询商家信息
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testGetStoreInfoByQRcode() throws GenericServiceException, GenericEntityException{
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "system"), false);

		String testStore1_idValue = "ccs-2d34a959-7b79-4162-8644-36a5e86068fc";
		String testStore2_idValue = "ccs-2f9402bc-9201-4867-b8bb-139848c929b5";

		GenericValue partyIdentification = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", STORE_ID_1, "partyIdentificationTypeId", "STORE_QR_CODE"), false);
		assertEquals( testStore1_idValue, partyIdentification.getString("idValue"));

		GenericValue partyIdentification2 = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", STORE_ID_2, "partyIdentificationTypeId", "STORE_QR_CODE"), false);
		assertEquals( testStore2_idValue, partyIdentification2.getString("idValue"));

		partyIdentification = CloudCardHelper.getPartyGroupByQRcodeNoException(testStore1_idValue, delegator);
		assertEquals(STORE_ID_1, partyIdentification.getString("partyId"));

		partyIdentification = CloudCardHelper.getPartyGroupByQRcodeNoException(testStore2_idValue, delegator);
		assertEquals(STORE_ID_2, partyIdentification.getString("partyId"));


		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("qrCode", "ccs-2d34a959-7b79-4162-8644-36a5e86068fc");
		Map<String, Object> resp = dispatcher.runSync("getStoreInfoByQRcode", ctx);

		assertTrue("Service 'getStoreInfoByQRcode' SHOULD result success", ServiceUtil.isSuccess(resp));

		String expectedStoreId = STORE_ID_1;
		String expectedStoreName = "单元测试用商家一";
		String storeId = (String) resp.get("storeId");
		String storeName = (String) resp.get("storeName");
		assertEquals("storeId should be expectedStoreId", expectedStoreId, storeId);
		assertEquals("storeName should be expectedStoreName", expectedStoreName, storeName);

		ctx.clear();
		ctx.put("userLogin", userLogin);
		ctx.put("qrCode", "ccs-1d34a959-7b79-4162-8644-36a5e86068fc");
		resp = dispatcher.runSync("getStoreInfoByQRcode", ctx);
		assertFalse("this call of service 'getStoreInfoByQRcode' input invalid qrCode,  SHOULD NOT result success", ServiceUtil.isSuccess(resp));

	}
	
	/**
	 * 测试开卡/充值
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testActivateCloudCardAndRecharge() throws GenericServiceException, GenericEntityException{

		// 生成2张新卡
		Map<String, Object> resp = callGenNewCloudCardCode(STORE_ID_1, "测试充值用", 2);
		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该只有2张
		List<GenericValue> cloudCardList = queryCardForStoreFromDB(STORE_ID_1, "测试充值用", "FNACT_CREATED");
		assertEquals("should has 2 cards here", 2, cloudCardList.size());

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
		BigDecimal amount = new BigDecimal(200.00f);
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, amount, "13913913913");

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
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1,  CloudCardHelper.ZERO, null);
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
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCode1_2, new BigDecimal(300.00f), "13913913913");
		assertFalse("Service 'activateCloudCardAndRecharge' [activate]  SHOULD NOT result success when the store is not the distributor of card", ServiceUtil.isSuccess(resp));

		// 测试9 卖卡额度不够 服务调用应该返回失败
		resp =  callActivateCloudCardAndRecharge(STORE_1_USER, cardCode1_1, new BigDecimal(9999999999.00f), null);
		assertFalse("Service 'activateCloudCardAndRecharge' SHOULD NOT result success when the amount of card is greate than the limit of store", ServiceUtil.isSuccess(resp));

	}
	
	/**
	 * 测试用户卡列表
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testMyCloudCards()  throws GenericServiceException, GenericEntityException{

		/********************* 卡数据准备 ***************************/
		// 一店 生成5张新卡
		String cardNameA = "商家一测试用户卡列表用";
		int countA = 5;

		Map<String, Object> resp = callGenNewCloudCardCode(STORE_ID_1, cardNameA, countA);
		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该只有5张
		List<GenericValue> cloudCardListA = queryCardForStoreFromDB(STORE_ID_1, cardNameA, "FNACT_CREATED");
		assertEquals("should has " + countA + " cards here", countA, cloudCardListA.size());

		// 全部调整成待激活的状态
		List<GenericValue> toBeStore = FastList.newInstance();
		for(GenericValue cc: cloudCardListA){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}

		// 二店 生成3张新卡
		int countB = 3;
		String cardNameB = "商家二测试用户卡列表用";

		resp = callGenNewCloudCardCode(STORE_ID_2, cardNameB, countB);
		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该只有2张
		List<GenericValue> cloudCardListB = queryCardForStoreFromDB(STORE_ID_2, cardNameB, "FNACT_CREATED");
		assertEquals("should has " + countB + " cards here", countB, cloudCardListB.size());

		// 全部调整成待激活的状态
		for(GenericValue cc: cloudCardListB){
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", cc.get("finAccountId")));
			finAccount.put("statusId", "FNACT_PUBLISHED");
			toBeStore.add(finAccount);
		}
		delegator.storeAll(toBeStore);

		String cardCodeA_1 = cloudCardListA.get(0).getString("finAccountCode");
		BigDecimal amount1 = new BigDecimal(200.00f).setScale(decimals, rounding);

		String cardCodeA_2 = cloudCardListA.get(1).getString("finAccountCode");
		BigDecimal amount2 = new BigDecimal(123.00f).setScale(decimals, rounding);

		String cardCodeA_3 = cloudCardListA.get(2).getString("finAccountCode");
		BigDecimal amount3 = new BigDecimal(155.12f).setScale(decimals, rounding);

		String cardCodeA_4 = cloudCardListA.get(3).getString("finAccountCode");
		BigDecimal amount4 = new BigDecimal(111.11f).setScale(decimals, rounding);
		
		String cardCodeA_5 = cloudCardListA.get(4).getString("finAccountCode");
		BigDecimal amount5 = new BigDecimal(222.22f).setScale(decimals, rounding);
		
		String cardCodeB_1 = cloudCardListB.get(0).getString("finAccountCode");
		BigDecimal amount6 = new BigDecimal(444.44f).setScale(decimals, rounding);
		
		String cardCodeB_2 = cloudCardListB.get(1).getString("finAccountCode");
		BigDecimal amount7 = new BigDecimal(600.01f).setScale(decimals, rounding);
		
		String cardCodeB_3 = cloudCardListB.get(2).getString("finAccountCode");
		BigDecimal amount8 = new BigDecimal(150.01f).setScale(decimals, rounding);

		String userTeleNumber_1 = "011011011011"; // 用户一手机号码
		String userTeleNumber_2 = "022022022022"; // 用户二手机号码

		/********************* 卡数据准备结束 ***************************/
		
		// 测试1 给 用户一 开一张卡，然后查询卡列表
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_1, amount1, userTeleNumber_1);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		String cardId = (String) resp.get("cardId"); //服务返回 卡Id
		String customerPartyId = (String) resp.get("customerPartyId"); //服务返回 客户Id

		List<GenericValue> cardList = queryCardForCustomerFromDB(customerPartyId, false);
		assertEquals("should has 1 cards here", 1, cardList.size());

		// 查询用户一应该只有一张卡
		resp = callMyCloudCards(userTeleNumber_1, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));

		Integer listSize = (Integer) resp.get("listSize");
		assertEquals("should has 1 cards here", 1, listSize.intValue()); 

		List<Map<String,Object>> cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		Map<String, Object> oneCard = cloudCardList.get(0);
		assertEquals("cardId should be equals", cardId, oneCard.get("cardId"));
		assertEquals("cardCode should be equals", cardCodeA_1, oneCard.get("cardCode"));
		assertEquals("cardBalance should be equals", amount1, oneCard.get("cardBalance"));
		assertEquals("distributorPartyId should be equals", STORE_ID_1, oneCard.get("distributorPartyId"));

		// 测试2 再给 用户一 连续开四张卡，再查询卡信息列表
		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_2, amount2, userTeleNumber_1);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_3, amount3, userTeleNumber_1);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_4, amount4, userTeleNumber_1);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_1_USER, cardCodeA_5, amount5, userTeleNumber_1);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callMyCloudCards(userTeleNumber_1, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		listSize = (Integer) resp.get("listSize");
		assertEquals("should has 5 cards here", 5, listSize.intValue());

		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		BigDecimal total = CloudCardHelper.ZERO;
		for(Map<String, Object> card: cloudCardList){
			total = total.add((BigDecimal)card.get("cardBalance"));
		}

		assertEquals("total cardBalance should be equals", 
				amount1.add(amount2).add(amount3).add(amount4).add(amount5).setScale(decimals, rounding), 
				total.setScale(decimals, rounding));

		// 测试3 给 用户二 连续开三张卡，再查询卡信息列表
		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCodeB_1, amount6, userTeleNumber_2);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCodeB_2, amount7, userTeleNumber_2);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callActivateCloudCardAndRecharge(STORE_2_USER, cardCodeB_3, amount8, userTeleNumber_2);
		assertTrue("Service 'activateCloudCardAndRecharge' SHOULD result success", ServiceUtil.isSuccess(resp));

		resp = callMyCloudCards(userTeleNumber_2, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		listSize = (Integer) resp.get("listSize");
		assertEquals("should has 3 cards here", 3, listSize.intValue());

		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		total = CloudCardHelper.ZERO;
		for(Map<String, Object> card: cloudCardList){
			total = total.add((BigDecimal)card.get("cardBalance"));
		}

		assertEquals("total cardBalance should be equals", 
				amount6.add(amount7).add(amount8).setScale(decimals, rounding), 
				total.setScale(decimals, rounding));

	}

/*	public void testCloudCardServicesOperations() throws GenericServiceException, GenericEntityException{
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "testSyy1"), false);
        Map<String, Object> ctx = new HashMap<String, Object>();
        //激活开卡业务
        List<String> organizationPartyIds = CloudCardHelper.getOrganizationPartyId(delegator, userLogin.getString("partyId"));
		BigDecimal amountBig = BigDecimal.ZERO; 

        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("teleNumber", "13811112227");
        ctx.put("cardCode", "02148456032481356127");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("200.00"))));
        ctx.put("userLogin", userLogin);
        Debug.logError( organizationPartyIds.get(0), null);
        Map<String, Object> resp = dispatcher.runSync("activateCloudCardAndRecharge", ctx);
        assertTrue("Service 'activateCloudCardAndRecharge' result success", ServiceUtil.isSuccess(resp));

        BigDecimal amount = (BigDecimal) resp.get("amount");
        BigDecimal cardBalance = (BigDecimal) resp.get("cardBalance");
        assertEquals("200.0", String.valueOf(amount));
        assertEquals("200.0", String.valueOf(cardBalance));
        ctx.clear();
        //充值业务
        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("cardId", "10000");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("200.00"))));
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("rechargeCloudCard", ctx, 2000,true);
        assertTrue("Service 'rechargeCloudCard' result success", ServiceUtil.isSuccess(resp));
      
        String customerPartyId = (String) resp.get("customerPartyId");
        amount = (BigDecimal) resp.get("amount");
        BigDecimal actualBalance = (BigDecimal) resp.get("actualBalance");
        assertEquals("1400.00", String.valueOf(actualBalance));
        assertEquals("10010", customerPartyId);
        ctx.clear();

        //支付服务
        ctx.put("organizationPartyId", organizationPartyIds.get(0));
        ctx.put("cardCode", "00812134736130161527");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("100.00"))));
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("cloudCardWithdraw", ctx, 2000,true);
        assertTrue("Service 'cloudCardWithdraw' result success", ServiceUtil.isSuccess(resp));

        amount = (BigDecimal) resp.get("amount");
        cardBalance = (BigDecimal) resp.get("cardBalance");
        customerPartyId = (String) resp.get("customerPartyId");
        assertEquals("100.0", String.valueOf(amount));
        assertEquals("1300.00", String.valueOf(cardBalance));
        assertEquals("10010", customerPartyId);
        ctx.clear();
        
        //卡授权
        Timestamp dateTime = UtilDateTime.nowTimestamp();
        ctx.put("teleNumber", "18711112222");
        ctx.put("cardId", "10000");
        ctx.put("amount", amountBig.add(BigDecimal.valueOf(UtilMisc.toDouble("100.00"))));
        ctx.put("fromDate", dateTime);
        ctx.put("thruDate", UtilDateTime.addDaysToTimestamp(dateTime, 1));
        ctx.put("userLogin", userLogin);
        resp = dispatcher.runSync("createCardAuth", ctx, 2000,true);
        assertTrue("Service 'cloudCardWithdraw' result success", ServiceUtil.isSuccess(resp));
        ctx.clear();
        
	}*/
	
	
	/**
	 * 调用genNewCloudCardCode服务
	 * @param organizationPartyId
	 * @param finAccountName
	 * @param quantity
	 * @return
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	private Map<String, Object> callGenNewCloudCardCode(String organizationPartyId, String finAccountName, int quantity) throws GenericServiceException, GenericEntityException{
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "system"), false);
		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("quantity", ""+quantity);
		ctx.put("currencyUomId", "CNY");
		ctx.put("finAccountName", finAccountName);
		ctx.put("organizationPartyId", organizationPartyId);
		return dispatcher.runSync("genNewCloudCardCode", ctx);
	}

	/**
	 * 直接从数据库查询商家卡列表
	 * @param organizationPartyId
	 * @param finAccountName
	 * @param statusId
	 * @return
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	private List<GenericValue> queryCardForStoreFromDB(String organizationPartyId, String finAccountName, String statusId) throws GenericServiceException, GenericEntityException {
		Map<String, String> lookupMap = UtilMisc.toMap("organizationPartyId", "Company");

		if(null != finAccountName){
			lookupMap.put("finAccountName", finAccountName);
		}

		lookupMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
		lookupMap.put("distributorPartyId", organizationPartyId);

		if(null != statusId){
			lookupMap.put("statusId", statusId);
		}
		return delegator.findByAnd("FinAccountAndPaymentMethodAndGiftCard", lookupMap);
	}

	/**
	 * 直接从数据库查询用户卡列表
	 * @param partyId 客户partyId
	 * @param filterByDate 是否使用 起止时间 过滤
	 * @return
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	private List<GenericValue> queryCardForCustomerFromDB(String partyId, boolean filterByDate) throws GenericServiceException, GenericEntityException {
		Map<String, String> lookupMap = UtilMisc.toMap("partyId", partyId);
		lookupMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("partyId", partyId,"finAccountTypeId", "GIFTCERT_ACCOUNT"));
		if(filterByDate){
			cond = EntityCondition.makeCondition(cond, EntityUtil.getFilterByDateExpr());
		}
		return delegator.findList("FinAccountAndPaymentMethodAndGiftCard", cond,null,null,null,false);
	}

	/**
	 * 调用充值开卡服务
	 * @param storeTeleNumber 店家 teleNumber
	 * @param cardCode
	 * @param amount
	 * @param teleNumber
	 * @return
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	private Map<String, Object> callActivateCloudCardAndRecharge(String storeTeleNumber, String cardCode, BigDecimal amount, String teleNumber)  throws GenericServiceException, GenericEntityException {
		
		GenericValue user = CloudCardHelper.getUserByTeleNumber(delegator, storeTeleNumber);
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", user.getString("userLoginId")), false);
		String organizationPartyId = CloudCardHelper.getOrganizationPartyId(delegator,  userLogin.getString("partyId")).get(0);

		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("organizationPartyId", organizationPartyId);
		ctx.put("teleNumber", teleNumber);
		ctx.put("cardCode", cardCode);
		ctx.put("amount", amount);
		return dispatcher.runSync("activateCloudCardAndRecharge", ctx);
	}
	
	/**
	 * 调用 查询用户卡信息列表服务
	 * @param userTeleNumber 用户开卡时候的手机号
	 * @param viewIndex 页码 0 开始
	 * @param viewSize 也大小
	 * @return
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	private Map<String, Object> callMyCloudCards(String userTeleNumber, Integer viewIndex, Integer viewSize)  throws GenericServiceException, GenericEntityException {
		
		GenericValue user = CloudCardHelper.getUserByTeleNumber(delegator, userTeleNumber);
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", user.getString("userLoginId")), false);

		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("viewIndex", viewIndex);
		ctx.put("viewSize", viewSize);
		return dispatcher.runSync("myCloudCards", ctx);
	}
	
}
