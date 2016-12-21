package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilNumber;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.testtools.OFBizTestCase;

import com.banfftech.cloudcard.CloudCardHelper;

import javolution.util.FastMap;

/**
 * <p>云卡单元测试基类，此类中设置一些测试需要用的 常量和 提供公共方法</p>
 * 注: 每个派生出去的测试类在执行测试的时候，父类中的test方法也会被执行，所以这个类里面最好不要有test方法
 */
public class CloudCardServicesTest extends OFBizTestCase {
	
	/**
	 *  商家一的id {@value}
	 */
	public static final String STORE_ID_1 = "testStore1";
	
	/**
	 *  商家一的店名 {@value}
	 */
	public static final String STORE_NAME_1 = "单元测试用商家一";
	
	/**
	 * 商家一的收银人员登录用手机号 {@value}
	 */
	public static final String STORE_1_USER = "15615615615";
	
	/**
	 * 商家一的二维码 {@value}
	 */
	public static final String STORE_1_QR_CODE = "ccs-2d34a959-7b79-4162-8644-36a5e86068fc"; 

	/**
	 *  商家二的id {@value}
	 */
	public static final String STORE_ID_2 = "testStore2";

	/**
	 *  商家二的店名 {@value}
	 */
	public static final String STORE_NAME_2 = "单元测试用商家二";

	/**
	 *  商家二的收银人员登录用手机号 {@value}
	 */
	public static final String STORE_2_USER = "15715715715";

	/**
	 *  商家二的二维码 {@value}
	 */
	public static final String STORE_2_QR_CODE = "ccs-2f9402bc-9201-4867-b8bb-139848c929b5";

	/**
	 *  一个无效的商家二维码 {@value}
	 */
	public static final String INVALID_STORE_QR_CODE = "ccs-1d34a959-7b79-4162-8644-36a5e86068fc";
	
	/**
	 *  一个不存在的商家id {@value}
	 */
	public static final String STORE_ID_NA_ = "testStore_NA_"; 


	public static int decimals = UtilNumber.getBigDecimalScale("finaccount.decimals");
    public static int rounding = UtilNumber.getBigDecimalRoundingMode("finaccount.rounding");
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(decimals, rounding);
	
	public CloudCardServicesTest(String name) {
		super(name);
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
	protected Map<String, Object> callGenNewCloudCardCode(String organizationPartyId, String finAccountName, int quantity) throws GenericServiceException, GenericEntityException{
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
	protected List<GenericValue> queryCardForStoreFromDB(String organizationPartyId, String finAccountName, String statusId) throws GenericServiceException, GenericEntityException {
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
	protected List<GenericValue> queryCardForCustomerFromDB(String partyId, boolean filterByDate) throws GenericServiceException, GenericEntityException {
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
	protected Map<String, Object> callActivateCloudCardAndRecharge(String storeTeleNumber, String cardCode, BigDecimal amount, String teleNumber)  throws GenericServiceException, GenericEntityException {
		
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
	protected Map<String, Object> callMyCloudCards(String userTeleNumber, Integer viewIndex, Integer viewSize)  throws GenericServiceException, GenericEntityException {
		
		GenericValue user = CloudCardHelper.getUserByTeleNumber(delegator, userTeleNumber);
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", user.getString("userLoginId")), false);

		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("viewIndex", viewIndex);
		ctx.put("viewSize", viewSize);
		return dispatcher.runSync("myCloudCards", ctx);
	}

	/**
	 * 调用 查询商家授信额度等信息的服务 
	 * @param storeTeleNumber  商家收银人员登录用手机号码
	 * @param storeId 商家ID
	 * @return
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	protected Map<String,Object> callGetLimitAndPresellInfo(String storeTeleNumber, String storeId)throws GenericServiceException, GenericEntityException {

		GenericValue user = CloudCardHelper.getUserByTeleNumber(delegator, storeTeleNumber);
		GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", user.getString("userLoginId")), false);

		Map<String, Object> ctx = FastMap.newInstance();
		ctx.put("userLogin", userLogin);
		ctx.put("organizationPartyId", storeId);
		return dispatcher.runSync("getLimitAndPresellInfo", ctx);
	}
}
