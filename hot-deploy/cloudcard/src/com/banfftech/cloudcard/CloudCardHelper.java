package com.banfftech.cloudcard;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
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

import javolution.util.FastList;
import javolution.util.FastMap;

/**
 * @author cy
 */
public class CloudCardHelper {
	
	public static final String module = CloudCardHelper.class.getName();
	
	public static final String DEFAULT_CURRENCY_UOM_ID = "CNY"; // 默认币种
	
	public static final String resourceError = "cloudcardErrorUiLabels";
	
	public static final String PLATFORM_PARTY_ID="Company"; // 平台partyId
	
	public static final String AUTH_CARD_CODE_PREFIX="auth:"; // 授权给别人的云卡卡号前缀
	
	/**
	 * 判断当前partyId是否为organizationPartyId的管理人员
	 * @param delegator
	 * @param userLogin
	 * @param organizationPartyId
	 */
	public static boolean isManager(Delegator delegator, String partyId, String organizationPartyId) {
		List<GenericValue> managerRels = PartyRelationshipHelper.getActivePartyRelationships(delegator, 
				UtilMisc.toMap("partyIdTo", partyId,
						"partyIdFrom",organizationPartyId,"roleTypeIdTo","MANAGER","roleTypeIdFrom","INTERNAL_ORGANIZATIO","partyRelationshipTypeId","EMPLOYMENT"));
		if(UtilValidate.isNotEmpty(managerRels)){
			return true;
		}
		return false;
	}
	
	/**
	 * 获取partyId所管理的商家organizationPartyId列表
	 * @param delegator
	 * @param userLogin
	 * @param organizationPartyId
	 */
	public static List<String> getOrganizationPartyId(Delegator delegator, String partyId) {
        List<EntityCondition> condList = FastList.newInstance();
        condList.add(EntityCondition.makeCondition("partyIdTo", partyId));
        condList.add(EntityCondition.makeCondition("roleTypeIdTo", "MANAGER"));
        condList.add(EntityCondition.makeCondition("roleTypeIdFrom", "INTERNAL_ORGANIZATIO"));
        condList.add(EntityCondition.makeCondition("partyRelationshipTypeId", "EMPLOYMENT"));
        condList.add(EntityUtil.getFilterByDateExpr());
        EntityCondition condition = EntityCondition.makeCondition(condList);

        List<GenericValue> partyRelationships = null;
        try {
            partyRelationships = delegator.findList("PartyRelationship", condition, null, null, null, false);
        } catch (GenericEntityException e) {
            Debug.logError(e, "Problem finding PartyRelationships. ", module);
            return null;
        }
        if (UtilValidate.isEmpty(partyRelationships)) {
        	return null;
        }
        return EntityUtil.getFieldListFromEntityList(partyRelationships, "partyIdFrom", true);
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
		Locale locale = (Locale) context.get("locale");

		String teleNumber = (String) context.get("teleNumber");
		String organizationPartyId = (String) context.get("organizationPartyId");
		Boolean ensureCustomerRelationship = (Boolean)context.get("ensureCustomerRelationship");
		if(ensureCustomerRelationship==null){
			ensureCustomerRelationship = Boolean.FALSE;
		}
		
		//TODO 密码随机生成？
		String currentPassword = (String) context.get("currentPassword");
		String defaultPwd = "123456";
		if(UtilValidate.isEmpty(currentPassword)){
			currentPassword =  defaultPwd;
		}
		String currentPasswordVerify = (String) context.get("currentPasswordVerify");
		if(UtilValidate.isEmpty(currentPasswordVerify)){
			currentPasswordVerify =  defaultPwd;
		}
		String customerPartyId;
		String customerUserLoginId;
		
		GenericValue customer;
		try {
			customer = EntityUtil.getFirst(delegator.findList("TelecomNumberAndUserLogin", 
					EntityCondition.makeCondition(
							EntityCondition.makeCondition(UtilMisc.toMap("contactNumber", teleNumber)), 
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
			Map<String, Object> createPersonMap = UtilMisc.toMap("userLogin", userLogin, "firstName", teleNumber, "lastName", "86");
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
			// UserLoginId通常是可以直接由用户输入的用户名，这里由系统生成，自定义个前缀CC  代表 Cloud Card，减少冲突
			customerUserLoginId ="CC"+delegator.getNextSeqId("UserLogin");
			GenericValue systemUser = (GenericValue) context.get("systemUser");
			if(null == systemUser){
				try {
					systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
				} catch (GenericEntityException e1) {
					Debug.logError(e1, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}
			}
			
			Map<String, Object> createUserLoginMap = UtilMisc.toMap("userLogin", systemUser);
			createUserLoginMap.put("userLoginId", customerUserLoginId);
			createUserLoginMap.put("partyId", customerPartyId); 
			createUserLoginMap.put("currentPassword", currentPassword); 
			createUserLoginMap.put("currentPasswordVerify", currentPasswordVerify); 
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
						UtilMisc.toMap("userLogin", systemUser, "contactMechPurposeTypeId", "AS_USER_LOGIN_ID", "partyId", customerPartyId,
								"contactNumber", teleNumber));
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
					"partyIdFrom", organizationPartyId,
					"partyIdTo", customerPartyId,
					"roleTypeIdFrom", "_NA_",
					"roleTypeIdTo", "CUSTOMER",
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
//		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String cardNumber = (String) context.get("cardNumber");
		String customerPartyId = (String) context.get("customerPartyId");
		String finAccountId = (String) context.get("finAccountId");
		String description = (String) context.get("description");
		Timestamp fromDate =(Timestamp)context.get("fromDate");
		if(UtilValidate.isEmpty(fromDate)){
			fromDate = UtilDateTime.nowTimestamp();
		}
		
		Timestamp thruDate =(Timestamp)context.get("thruDate");

		String paymentMethodId;
		
		GenericValue systemUser = (GenericValue) context.get("systemUser");
		if(null == systemUser){
			try {
				systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
			} catch (GenericEntityException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
		}
		
		
		Map<String, Object> giftCardMap = FastMap.newInstance();
		giftCardMap.put("userLogin", systemUser);
		giftCardMap.put("partyId", customerPartyId); 
		giftCardMap.put("description", description); 
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
	 * @param organizationPartyId 商家partyId
	 * @return
	 */
	public static GenericValue getCreditLimitAccount(Delegator delegator,  String organizationPartyId){
		return getCreditLimitAccount(delegator, organizationPartyId, true);
	}
	
	
	/**
	 * 获取商家用于扣减开卡限额的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @param useCache
	 * @return
	 */
	public static GenericValue getCreditLimitAccount(Delegator delegator,  String organizationPartyId, boolean useCache) {
		EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("organizationPartyId", PLATFORM_PARTY_ID,
				"ownerPartyId", organizationPartyId, "finAccountTypeId", "SVCCRED_ACCOUNT", "statusId", "FNACT_ACTIVE"));
		GenericValue partyGroupFinAccount = null;
		try {
			partyGroupFinAccount = EntityUtil
					.getFirst(delegator.findList("FinAccount", EntityCondition.makeCondition(cond, dateCond), null,
							UtilMisc.toList("-" + ModelEntity.STAMP_FIELD), null, useCache));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		
		return partyGroupFinAccount;
	}
	
	/**
	 * 获取商家用于收款的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @return
	 */
	public static GenericValue getReceiptAccount(Delegator delegator,  String organizationPartyId) {
		return getReceiptAccount(delegator, organizationPartyId, true);
	}
	
	/**
	 * 获取商家用于收款的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @param useCache
	 * @return
	 */
	public static GenericValue getReceiptAccount(Delegator delegator,  String organizationPartyId, boolean useCache) {
		EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("organizationPartyId", PLATFORM_PARTY_ID,
				"ownerPartyId", organizationPartyId, "finAccountTypeId", "DEPOSIT_ACCOUNT", "statusId", "FNACT_ACTIVE"));
		GenericValue partyGroupFinAccount = null;
		try {
			partyGroupFinAccount = EntityUtil
					.getFirst(delegator.findList("FinAccount", EntityCondition.makeCondition(cond, dateCond), null,
							UtilMisc.toList("-" + ModelEntity.STAMP_FIELD), null, useCache));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		
		return partyGroupFinAccount;
	}
	
	
    /**
     * 根据二维码查询卡信息
     * @param cardCode 二维码信息
     * @param delegator
     * @return FinAccountAndPaymentMethodAndGiftCard
     * @throws GenericEntityException
     */
    public static GenericValue getCloudCardAccountFromCode(String cardCode, Delegator delegator) throws GenericEntityException {
        if (UtilValidate.isEmpty(cardCode)) {
            return null;
        }
        // 因为FinAccount.finAccountCode 与 GiftCard.cardNumber都是加密存储的，需要先对输入后的code加密后才能作为条件进行查询
        // 先查 GiftCard.cardNumber，再查FinAccount,因为别人可能直接拿物理的卡来扫描
        // 考虑到 finAccount可以授权给别人，关联多个giftCard，二维码就应该是有多个，否则可能会出现授权被回收后，有人用截屏的二维码图片进行支付
        // FIXME 二维码支付的安全性应当进一步设计,比如用户需要进行支付的时候，请求后台生成个码，拼接到二维码里面去？
       
        GenericValue encryptedGiftCard = delegator.makeValue("GiftCard", UtilMisc.toMap("cardNumber",cardCode));
        delegator.encryptFields(encryptedGiftCard);
        String encryptedCardNumber = encryptedGiftCard.getString("cardNumber");
        List<GenericValue> giftCards = delegator.findByAnd("FinAccountAndPaymentMethodAndGiftCard", UtilMisc.toMap("cardNumber", encryptedCardNumber));
        giftCards =  EntityUtil.filterByDate(giftCards);
        
        if (UtilValidate.isEmpty(giftCards)) {
        	//扫描的物理卡 去FinAccount里面找
        	GenericValue encryptedFinAccount = delegator.makeValue("FinAccount", UtilMisc.toMap("finAccountCode",cardCode));
            delegator.encryptFields(encryptedFinAccount);
            String encryptedFinAccountCode = encryptedFinAccount.getString("finAccountCode");
            EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
            EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("finAccountCode", encryptedFinAccountCode));
            
            // 既然是物理卡，fromDate应该是最小的吧
            List<GenericValue> accounts = delegator.findList("FinAccountAndPaymentMethodAndGiftCard", EntityCondition.makeCondition(cond, dateCond), null, UtilMisc.toList("fromDate"), null, false);
            accounts = EntityUtil.filterByDate(accounts);
            return EntityUtil.getFirst(accounts);
        } else if (giftCards.size() > 1) {
            Debug.logError("一个二维码找到多张卡？", module);
            return null;
        } else {
            return giftCards.get(0);
        }
    }
    /**
     * 根据卡ID查询卡信息
     * @param cardCode 二维码信息
     * @param delegator
     * @return FinAccountAndPaymentMethodAndGiftCard
     * @throws GenericEntityException
     */
    public static GenericValue getCloudCardAccountFromPaymentMethodId(String paymentMethodId, Delegator delegator) throws GenericEntityException {
    	if (UtilValidate.isEmpty(paymentMethodId)) {
    		return null;
    	}
    	return EntityUtil.getFirst( EntityUtil.filterByDate(delegator.findByAnd("FinAccountAndPaymentMethodAndGiftCard", UtilMisc.toMap("paymentMethodId", paymentMethodId))));
    }
    
    /**
     * 生成卡云卡号
     * @param delegator
     * @return 卡号（二维码）
     * @throws GenericEntityException
     */
    public static String generateCloudCardCode(Delegator delegator) throws GenericEntityException {
        Random rand = new Random();
        boolean foundUniqueNewCode = false;
        String newCardCode = null;
        long count = 0;

        while (!foundUniqueNewCode) {
            String number = "";
            for (int i = 0; i < 19; i++) {
                int randInt = rand.nextInt(9);
                number = number + randInt;
            }
            int check = UtilValidate.getLuhnCheckDigit(number);
            newCardCode = number + (check==10 ? "X" : check);
        	
        	GenericValue encryptedGiftCard = delegator.makeValue("FinAccount", UtilMisc.toMap("finAccountCode",newCardCode));
            delegator.encryptFields(encryptedGiftCard);
            
            List<GenericValue> existingAccountsWithCode = delegator.findByAnd("FinAccount", UtilMisc.toMap("finAccountCode", encryptedGiftCard.getString("finAccountCode")));
            if (UtilValidate.isEmpty(existingAccountsWithCode)) {
                foundUniqueNewCode = true;
            }

            count++;
            if (count > 1000) {
                throw new GenericEntityException("Unable to locate unique FinAccountCode! Length [20]");
            }
        }

        return newCardCode.toString();
    }
    
    
	/**
	 * 通过查询FinAccountRole中是否有有效的 roleTypeId=SHAREHOLDER 的记录，来判断卡是否已经被授权其他用户使用
	 * @param cloudCard
	 * @param delegator
	 * @return
	 */
	public static boolean cardIsAuthorized(GenericValue cloudCard, Delegator delegator) {
		if (null == cloudCard)
			return false;
		String finAccountId = cloudCard.getString("finAccountId");
		EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition cond = EntityCondition
				.makeCondition(UtilMisc.toMap("finAccountId", finAccountId, "roleTypeId", "SHAREHOLDER"));
		try {
			List<GenericValue> findList = delegator.findList("FinAccountRole",
					EntityCondition.makeCondition(dateCond, cond), null, null, null, false);
			if (findList.size() > 0) {
				return true;
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		return false;
	}
}
