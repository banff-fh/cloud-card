package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.testtools.OFBizTestCase;

import com.banfftech.cloudcard.CloudCardHelper;

import javolution.util.FastMap;

/**
 * @author subenkun
 *
 */
public class CloudCardServicesTest extends OFBizTestCase {

	public CloudCardServicesTest(String name) {
		super(name);
	}

	/**
	 * 测试 生成云卡的服务
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testGenNewCloudCardCode() throws GenericServiceException, GenericEntityException{
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "ccadmin"), false);
		
		// 生成15张新卡
		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("quantity", "15");
		ctx.put("currencyUomId", "CNY");
		ctx.put("finAccountName", "测试商店一的卡15张");
		ctx.put("organizationPartyId", "testStore1");
		Map<String, Object> resp = dispatcher.runSync("genNewCloudCardCode", ctx);
		
		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));
		
		// 查询生成的卡条数，应该只有15张
		Map<String, String> lookupMap = UtilMisc.toMap("organizationPartyId", "Company");
		lookupMap.put("finAccountName", "测试商店一的卡15张");
		lookupMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
		lookupMap.put("distributorPartyId", "testStore1");
		lookupMap.put("statusId", "FNACT_CREATED");
		List<GenericValue> cloudCardList = delegator.findByAnd("FinAccountAndPaymentMethodAndGiftCard", lookupMap);

		assertEquals("this service call for testStore1 should generate 15 cards", 15, cloudCardList.size());
		
		// 为店家二生成100张新卡
		ctx.clear();
		ctx.put("userLogin", userLogin);
		ctx.put("quantity", "100");
		ctx.put("currencyUomId", "CNY");
		ctx.put("finAccountName", "测试商店二的卡");
		ctx.put("organizationPartyId", "testStore2");
		resp = dispatcher.runSync("genNewCloudCardCode", ctx);
		
		assertTrue("Service 'genNewCloudCardCode' for testStore2 SHOULD result success", ServiceUtil.isSuccess(resp));
		
		// 查询生成的卡条数，应该有100张
		lookupMap = UtilMisc.toMap("organizationPartyId", "Company");
		lookupMap.put("finAccountName", "测试商店二的卡");
		lookupMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
		lookupMap.put("distributorPartyId", "testStore2");
		lookupMap.put("statusId", "FNACT_CREATED");
		cloudCardList = delegator.findByAnd("FinAccountAndPaymentMethodAndGiftCard", lookupMap);

		assertEquals("this service call for testStore2 should generate 15 cards", 100, cloudCardList.size());
		
		// 不存在的店家服务应该调用失败
/*		ctx.clear();
		ctx.put("userLogin", userLogin);
		ctx.put("quantity", "1");
		ctx.put("currencyUomId", "CNY");
		ctx.put("finAccountName", "不存在的店家不应该出现的卡");
		ctx.put("organizationPartyId", "testStore_NA_");
		resp = dispatcher.runSync("genNewCloudCardCode", ctx);
		
		assertFalse("Service 'genNewCloudCardCode' for testStore_NA_  SHOULD NOT result success", ServiceUtil.isSuccess(resp));
		*/
		// 再为商店二生成30张卡
		ctx.clear();
		ctx.put("userLogin", userLogin);
		ctx.put("quantity", "30");
		ctx.put("currencyUomId", "CNY");
		ctx.put("finAccountName", "测试商店二的卡");
		ctx.put("organizationPartyId", "testStore2");
		resp = dispatcher.runSync("genNewCloudCardCode", ctx);

		assertTrue("Service 'genNewCloudCardCode' for testStore2 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该有100张+30张 = 130张
		lookupMap = UtilMisc.toMap("organizationPartyId", "Company");
		lookupMap.put("finAccountName", "测试商店二的卡");
		lookupMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
		lookupMap.put("distributorPartyId", "testStore2");
		lookupMap.put("statusId", "FNACT_CREATED");
		cloudCardList = delegator.findByAnd("FinAccountAndPaymentMethodAndGiftCard", lookupMap);

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
		
		GenericValue partyIdentification = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", "testStore1", "partyIdentificationTypeId", "STORE_QR_CODE"), false);
		assertEquals( testStore1_idValue, partyIdentification.getString("idValue"));
		
		GenericValue partyIdentification2 = delegator.findOne("PartyIdentification", UtilMisc.toMap("partyId", "testStore2", "partyIdentificationTypeId", "STORE_QR_CODE"), false);
		assertEquals( testStore2_idValue, partyIdentification2.getString("idValue"));
		
		partyIdentification = CloudCardHelper.getPartyGroupByQRcodeNoException(testStore1_idValue, delegator);
		assertEquals("testStore1", partyIdentification.getString("partyId"));
		
		partyIdentification = CloudCardHelper.getPartyGroupByQRcodeNoException(testStore2_idValue, delegator);
		assertEquals("testStore2", partyIdentification.getString("partyId"));
		
		
		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("qrCode", "ccs-2d34a959-7b79-4162-8644-36a5e86068fc");
		Map<String, Object> resp = dispatcher.runSync("getStoreInfoByQRcode", ctx);
		
		assertTrue("Service 'getStoreInfoByQRcode' SHOULD result success", ServiceUtil.isSuccess(resp));

		String expectedStoreId = "testStore1";
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
}
