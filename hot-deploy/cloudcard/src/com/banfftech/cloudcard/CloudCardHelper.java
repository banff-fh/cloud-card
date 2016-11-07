package com.banfftech.cloudcard;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.party.party.PartyRelationshipHelper;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastMap;

/**
 * @author cy
 */
public class CloudCardHelper {
	
	public static final String module = CloudCardHelper.class.getName();
	
	public static final String DEFAULT_CURRENCY_UOM_ID = "CNY";
	
	public static final String resourceError = "cloudcardErrorUiLabels";
	

	/**
	 * 判断当前partyId是否为organizationPartyId的管理人员
	 * @param delegator
	 * @param userLogin
	 * @param organizationPartyId
	 */
	public static boolean isManager(Delegator delegator, String partyId, String organizationPartyId) {
		List<GenericValue> managerRels = PartyRelationshipHelper.getActivePartyRelationships(delegator, 
				UtilMisc.toMap("partyIdFrom", partyId,
						"partyIdTo",organizationPartyId,"roleTypeIdFrom","MANAGER","roleTypeIdTo","INTERNAL_ORGANIZATIO","partyRelationshipTypeId","EMPLOYMENT"));
		if(UtilValidate.isNotEmpty(managerRels)){
			return true;
		}
		return false;
	}
	

	/**
	 * 根据电话号码检查是否有关联的注册用户，有则返回customerPartyId 和 customerUserLoginId
	 * 没有则 创建一个用户与电话号码关联，并返回新创建的 customerPartyId 和 customerUserLoginId
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getOrCreateCustomer(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String telNumber = (String) context.get("telNumber");
		Locale locale = (Locale) context.get("locale");
		String organizationPartyId = (String) context.get("organizationPartyId");
		Boolean ensureCustomerRelationship = (Boolean)context.get("ensureCustomerRelationship");
		if(ensureCustomerRelationship==null){
			ensureCustomerRelationship = Boolean.FALSE;
		}
		
		//TODO 密码随机生成？
		String currentPassword = (String) context.get("currentPassword");
		if(UtilValidate.isEmpty(currentPassword)){
			currentPassword =  "123456";
		}
		String currentPasswordVerify = (String) context.get("currentPasswordVerify");
		if(UtilValidate.isEmpty(currentPasswordVerify)){
			currentPasswordVerify =  "123456";
		}
		String customerPartyId;
		String customerUserLoginId;
		
		GenericValue customer;
		try {
			customer = EntityUtil.getFirst(delegator.findList("TelecomNumberAndUserLogin", 
					EntityCondition.makeCondition(
							EntityCondition.makeCondition(UtilMisc.toMap("contactNumber", telNumber)), 
							EntityUtil.getFilterByDateExpr()), null, UtilMisc.toList("partyId DESC"), null, false));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if(customer != null){
			customerPartyId = (String) customer.get("partyId");
			customerUserLoginId = (String) customer.get("userLoginId");
		}else{
			// 由于createPersonAndUserLogin 会自动新启一个事务，后面若失败，不能回滚，
			// 所以分步调用 createPerson 和 createUserLogin
			Map<String, Object> createPersonMap = UtilMisc.toMap("userLogin", userLogin, "firstName", telNumber, "lastName", "86");
			createPersonMap.put("preferredCurrencyUomId", DEFAULT_CURRENCY_UOM_ID);
			Map<String, Object> personOutMap;
			try {
				personOutMap = dispatcher.runSync("createPerson", createPersonMap);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(personOutMap)) {
				return personOutMap;
			}
			customerPartyId = (String) personOutMap.get("partyId");
			//TODO,UserLoginId通常是可以直接由用户输入的用户名，这里由系统生成，自定义个前缀CC  代表 Cloud Card，减少冲突
			customerUserLoginId ="CC"+delegator.getNextSeqId("UserLogin");
			GenericValue systemUser;
			try {
				systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
			} catch (GenericEntityException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			
			Map<String, Object> createUserLoginMap = UtilMisc.toMap("userLogin", systemUser);
			createUserLoginMap.put("userLoginId", customerUserLoginId);
			createUserLoginMap.put("partyId", customerPartyId); 
			createUserLoginMap.put("currentPassword", currentPassword); 
			createUserLoginMap.put("currentPasswordVerify", currentPasswordVerify); //TODO
			createUserLoginMap.put("requirePasswordChange", "Y");
			
			Map<String, Object> userLoginOutMap;
			try {
				userLoginOutMap = dispatcher.runSync("createUserLogin", createUserLoginMap);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(userLoginOutMap)) {
				return userLoginOutMap;
			}

			Map<String, Object> partyTelecomOutMap;
			try {
				partyTelecomOutMap = dispatcher.runSync("createPartyTelecomNumber", 
						UtilMisc.toMap("userLogin", userLogin, "contactMechPurposeTypeId", "AS_USER_LOGIN_ID", "partyId", customerPartyId,
								"contactNumber", telNumber));
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(partyTelecomOutMap)) {
				return partyTelecomOutMap;
			}
		}
		
		
		if(ensureCustomerRelationship){
			// 若客户与本商家没有客户关系,则建立关系
			Map<String,Object> partyRelationshipValues = UtilMisc.toMap(
					"userLogin", userLogin,
					"partyIdFrom", customerPartyId,
					"partyIdTo", organizationPartyId,
					"roleTypeIdFrom", "CUSTOMER",
					"roleTypeIdTo", "INTERNAL_ORGANIZATIO",
					"partyRelationshipTypeId", "CUSTOMER_REL"
					);
			List<GenericValue> relations = PartyRelationshipHelper.getActivePartyRelationships(delegator, partyRelationshipValues);
			if (UtilValidate.isEmpty(relations)) {
				Map<String, Object> relationOutMap;
				try {
					dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("partyId", customerPartyId, "roleTypeId", "CUSTOMER"));
					relationOutMap = dispatcher.runSync("createPartyRelationship", partyRelationshipValues);
				} catch (GenericServiceException e) {
					Debug.logError(e, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}
				if (ServiceUtil.isError(relationOutMap)) {
					return relationOutMap;
				}
			}
		}
		Map<String, Object> retMap =  ServiceUtil.returnSuccess();
		retMap.put("customerPartyId", customerPartyId);
		retMap.put("customerUserLoginId", customerUserLoginId);
		return retMap;
	}
	
	/**
	 * 为FinAccount创建GiftCard
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> createPaymentMethodAndGiftCard(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String cardNumber = (String) context.get("cardNumber");
		String customerPartyId = (String) context.get("customerPartyId");
		String finAccountId = (String) context.get("finAccountId");
		String description = (String) context.get("description");
		Timestamp fromDate =(Timestamp)context.get("fromDate");
		Timestamp thruDate =(Timestamp)context.get("thruDate");

		String paymentMethodId;
		Map<String, Object> giftCardMap = FastMap.newInstance();
		giftCardMap.put("userLogin", userLogin);
		giftCardMap.put("partyId", customerPartyId); 
		giftCardMap.put("description", description);  //TODO partyGroup.get("groupName")+" 的卡"
		giftCardMap.put("cardNumber", cardNumber);
		Map<String, Object> giftCardOutMap;
		try {
			giftCardOutMap = dispatcher.runSync("createGiftCard", giftCardMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		// 关联 paymentMethod 与 finAccount
		paymentMethod.set("finAccountId", finAccountId);
		paymentMethod.set("fromDate", fromDate);
		paymentMethod.set("thruDate", thruDate);
		try {
			paymentMethod.store();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		return giftCardOutMap;
	}
	
	/**
	 * 获取商家用于扣减开卡限额的金融账户
	 * @param delegator
	 * @param organizationPartyId
	 * @return
	 */
	public static GenericValue getCreditLimitAccount(Delegator delegator,  String organizationPartyId) {
		EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("organizationPartyId", organizationPartyId,
				"ownerPartyId", organizationPartyId, "finAccountTypeId", "BANK_ACCOUNT", "statusId", "FNACT_ACTIVE"));
		GenericValue partyGroupFinAccount = null;
		try {
			partyGroupFinAccount = EntityUtil
					.getFirst(delegator.findList("FinAccount", EntityCondition.makeCondition(cond, dateCond), null,
							UtilMisc.toList("-" + ModelEntity.STAMP_FIELD), null, false));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		
		return partyGroupFinAccount;
	}
}
