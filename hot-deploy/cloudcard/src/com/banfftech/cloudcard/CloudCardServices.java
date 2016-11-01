package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastMap;

/**
 * @author subenkun
 *
 */
public class CloudCardServices {
	
	public static final String module = CloudCardServices.class.getName();
	
	public static final String DEFAULT_CURRENCY_UOM_ID = "CNY";
	
	/**
	 * 查询卡信息
	 */
	public static Map<String, Object> findFinAccountByOwnerPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		String ownerPartyId = (String) context.get("ownerPartyId");
		Integer viewIndex = (Integer) context.get("viewIndex");
		Integer viewSize = (Integer) context.get("viewSize");

		Map<String, Object> inputFieldMap = FastMap.newInstance();
		inputFieldMap.put("ownerPartyId", ownerPartyId);
		inputFieldMap.put("statusId", "FNACT_ACTIVE");

		Map<String, Object> ctxMap = FastMap.newInstance();
		ctxMap.put("inputFields", inputFieldMap);
		ctxMap.put("entityName", "FinAccount");
		ctxMap.put("orderBy", "finAccountId");
		ctxMap.put("viewIndex", viewIndex);
		ctxMap.put("viewSize", viewSize);

		Map<String, Object> faResult = null;
		try {
			faResult = dispatcher.runSync("performFindList", ctxMap);
		} catch (GenericServiceException e) {
			e.printStackTrace();
		}

		List<GenericValue> retList = UtilGenerics.checkList(faResult.get("list"));

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("finAccountList", retList);
		return result;
	}

	/**
	 * 查询交易流水
	 */
	public static Map<String, Object> findPaymentByPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		String partyIdFrom = (String) context.get("partyIdFrom");
		String partyIdTo = (String) context.get("partyIdTo");
		String paymentTypeId = (String) context.get("paymentTypeId");
		Integer viewIndex = (Integer) context.get("viewIndex");
		Integer viewSize = (Integer) context.get("viewSize");

		Map<String, Object> inputFieldMap = FastMap.newInstance();
		inputFieldMap.put("partyIdFrom", partyIdFrom);
		inputFieldMap.put("partyIdTo", partyIdTo);
		inputFieldMap.put("paymentTypeId", paymentTypeId);

		Map<String, Object> ctxMap = FastMap.newInstance();
		ctxMap.put("inputFields", inputFieldMap);
		ctxMap.put("entityName", "PaymentAndTypePartyNameView");
		ctxMap.put("orderBy", "effectiveDate");
		ctxMap.put("viewIndex", viewIndex);
		ctxMap.put("viewSize", viewSize);

		Map<String, Object> paymentResult = null;
		try {
			paymentResult = dispatcher.runSync("performFindList", ctxMap);
		} catch (GenericServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("paymentList", paymentResult.get("list"));
		return result;
	}
	
	
	/**
	 * 卡授权
	 */
	public static Map<String, Object> createCardAuth(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Map<String, Object> result = null;
		String telNumber = (String) context.get("telNumber");
		String finAccountId = (String) context.get("finAccountId");
		String amount = (String) context.get("amount");
		Timestamp fromDate = (Timestamp) context.get("fromDate");
		Timestamp thruDate = (Timestamp) context.get("thruDate");

		try {
			// 授权时判断用户是否存在
			GenericValue person = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", telNumber));
			// 如果用户不存在，该用户设置为新用户
			if (person == null) {
				Map<String, Object> personAndUserLoginMap = FastMap.newInstance();
				personAndUserLoginMap.put("userLoginId", (String) context.get("telNumber"));
				personAndUserLoginMap.put("currentPassword", context.get("currentPassword"));
				personAndUserLoginMap.put("currentPasswordVerify", context.get("currentPasswordVerify"));
				personAndUserLoginMap.put("enabled", "Y");
				dispatcher.runSync("createPersonAndUserLogin", personAndUserLoginMap);
			}
						
			
		} catch (GenericServiceException | GenericEntityException e) {
			// TODO Auto-generated catch block
			result = ServiceUtil.returnError("create failed");
			e.printStackTrace();
		}

		result = ServiceUtil.returnSuccess();
		return result;
	}

	/**
	 * 给用户开卡
	 * 	1、根据telNumber查找用户，不存在则创建
	 *	2、创建FinAccount，关联卡上二维码等信息。
	 *	3、充值
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> createCloudCard(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String telNumber = (String) context.get("telNumber");
		String cardCode = (String) context.get("cardCode");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		
		String customerPartyId = "";
		String customerUserLoginId = "";
		String finAccountId = "";
		String paymentMethodId = "";
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if(partyGroup == null){
			Debug.logError("商户："+organizationPartyId + "不存在", module);
			return ServiceUtil.returnError("商户不存在");
		}
			
		// 1、根据telNumber查找用户，不存在则创建
		GenericValue customer;
		try {
			customer = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", telNumber));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if(customer != null){
			customerPartyId = (String) customer.get("partyId");
			customerUserLoginId = (String) customer.get("userLoginId");
		}else{
			Map<String, Object> createCustomerMap = UtilMisc.toMap("userLogin", userLogin, "userLoginId", telNumber, "firstName", telNumber, "lastName", "86");
			createCustomerMap.put("currentPassword", "123456"); //TODO 密码随机生成？
			createCustomerMap.put("currentPasswordVerify", "123456"); //TODO
			createCustomerMap.put("requirePasswordChange", "Y");
			createCustomerMap.put("preferredCurrencyUomId", DEFAULT_CURRENCY_UOM_ID);
			Map<String, Object> customerOutMap;
			try {
				customerOutMap = dispatcher.runSync("createPersonAndUserLogin", createCustomerMap);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(e.getMessage());
			}
			if (ServiceUtil.isError(customerOutMap)) {
				return customerOutMap;
			}
			GenericValue newUserLogin = (GenericValue) customerOutMap.get("newUserLogin");
			customerPartyId = (String) customerOutMap.get("partyId");
			customerUserLoginId = (String) newUserLogin.get("userLoginId");
		}
				
			
		// 2、创建finAccount 和 paymentMethod
		Map<String, Object> finAccountMap = FastMap.newInstance();
		finAccountMap.put("userLogin", userLogin);
		finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT"); //TODO 类型待定
		finAccountMap.put("finAccountName", partyGroup.get("groupName")+" 的卡");  //TODO
		finAccountMap.put("finAccountCode", cardCode);
		finAccountMap.put("currencyUomId", DEFAULT_CURRENCY_UOM_ID);
		finAccountMap.put("organizationPartyId", organizationPartyId);
		finAccountMap.put("ownerPartyId", customerPartyId);
		
		Map<String, Object> finAccountOutMap;
		try {
			finAccountOutMap = dispatcher.runSync("createFinAccount", finAccountMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (ServiceUtil.isError(finAccountOutMap)) {
			return finAccountOutMap;
		}
		finAccountId = (String) finAccountOutMap.get("finAccountId");
		
		// 给卡主 OWNER 角色
		Map<String, Object> finAccountRoleOutMap;
		try {
			finAccountRoleOutMap = dispatcher.runSync("createFinAccountRole", UtilMisc.toMap("finAccountId", finAccountId, "partyId", "customerPartyId", "roleTypeId","OWNER"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(e1.getMessage());
		}
		if (ServiceUtil.isError(finAccountRoleOutMap)) {
			return finAccountRoleOutMap;
		}
		
		// 创建PaymentMethod  GiftCard
		Map<String, Object> giftCardMap = FastMap.newInstance();
		giftCardMap.put("userLogin", userLogin);
		giftCardMap.put("partyId", customerPartyId); 
		giftCardMap.put("description", partyGroup.get("groupName")+" 的卡");  //TODO
		giftCardMap.put("cardNumber", cardCode);
		Map<String, Object> giftCardOutMap;
		try {
			giftCardOutMap = dispatcher.runSync("createGiftCard", giftCardMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}
		paymentMethodId = (String) giftCardOutMap.get("paymentMethodId");
		GenericValue paymentMethod;
		try {
			paymentMethod = delegator.findByPrimaryKey("PaymentMethod", UtilMisc.toMap("paymentMethodId", paymentMethodId));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		// 关联 paymentMethod 与 finAccount
		paymentMethod.set("finAccountId", finAccountId);
		try {
			paymentMethod.store();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}

		// 3、充值
		
		
		result.put("customerPartyId", customerPartyId);
		result.put("customerUserLoginId", customerUserLoginId);
		result.put("amount", amount);
		result.put("actualBalance", amount);
		result.put("finAccountId", finAccountId);
		
		return result;
	}
	
	
	/**
	 *	充值
	 *   1、扣商户开卡余额
	 *   2、存入用户账户
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> rechargeCloudCard(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String finAccountId = (String) context.get("finAccountId");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		String partyGroupFinAccountId = "";
		Map<String, Object> result = ServiceUtil.returnSuccess();
		try {
			GenericValue partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			if(UtilValidate.isEmpty(partyGroup)){
				return ServiceUtil.returnError("商户不存在");
			}
			
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount", UtilMisc.toMap("finAccountId", finAccountId));
			if(UtilValidate.isEmpty(finAccount)){
				return ServiceUtil.returnError("用户账户不存在");
			}
			
			//  1、扣商户开卡余额
			// 获取商户金融账户
			EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
			EntityCondition cond = EntityCondition.makeCondition(
					UtilMisc.toMap("organizationPartyId", organizationPartyId, "ownerPartyId", organizationPartyId,
							"finAccountTypeId", "BANK_ACCOUNT", "statusId", "FNACT_ACTIVE")
					);
			GenericValue partyGroupFinAccount = EntityUtil.getFirst(delegator.findList("FinAccount", EntityCondition.makeCondition(cond, dateCond), null, 
					UtilMisc.toList("createdStamp DESC"), null, true));
			if(UtilValidate.isEmpty(partyGroupFinAccount)){
				return ServiceUtil.returnError("商户没有金融账户");
			}
			partyGroupFinAccountId = (String) partyGroupFinAccount.get("finAccountId");
			
			// 检查开卡余额是否够用
			BigDecimal actualBalance = partyGroupFinAccount.getBigDecimal("actualBalance");
			if(actualBalance.compareTo(amount)<0){
				return ServiceUtil.returnError("商户卖卡额度不足, 余额：" + actualBalance.toPlainString());
			}
			
			EntityCondition pgPmCond = EntityCondition.makeCondition(
					UtilMisc.toMap("partyId", organizationPartyId, "paymentMethodTypeId", "FIN_ACCOUNT", "finAccountId", partyGroupFinAccountId));
			GenericValue partyGroupPaymentMethod = EntityUtil.getFirst(delegator.findList("PaymentMethod", EntityCondition.makeCondition(pgPmCond, dateCond), null,
					UtilMisc.toList("createdStamp DESC"), null, true));
			
			// 商户账户扣款（扣减开卡余额）
			

		} catch (GenericEntityException e) {
			// TODO
		}
		
		return result;
	}
}
