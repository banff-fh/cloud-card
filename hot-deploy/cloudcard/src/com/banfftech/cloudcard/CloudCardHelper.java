package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilNumber;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
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

	/**
	 * 默认币种 {@value}
	 */
	public static final String DEFAULT_CURRENCY_UOM_ID = "CNY"; 

	public static final String resourceError = "cloudcardErrorUiLabels";

	/**
	 * 平台partyId {@value}
	 */
	public static final String PLATFORM_PARTY_ID="Company";

	/**
	 * 授权给别人的云卡卡号前缀 {@value}
	 */
	public static final String AUTH_CARD_CODE_PREFIX="auth:";
	
	/**
	 * 商家二维码前缀 {@value}
	 */
	public static final String STORE_QR_CODE_PREFIX="ccs-";
	
	 public static int decimals = UtilNumber.getBigDecimalScale("finaccount.decimals");
     public static int rounding = UtilNumber.getBigDecimalRoundingMode("finaccount.rounding");
     public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(decimals, rounding);
	
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
		
		GenericValue systemUser = (GenericValue) context.get("systemUser");
		if(null == systemUser){
			try {
				systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
				context.put("systemUser", systemUser);
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
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
			customer = getUserByTeleNumber(delegator, teleNumber);
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
					"userLogin", systemUser,
					"partyIdFrom", organizationPartyId,
					"partyIdTo", customerPartyId,
					"roleTypeIdFrom", "_NA_",
					"roleTypeIdTo", "CUSTOMER",
					"partyRelationshipTypeId", "CUSTOMER_REL"
					);
			List<GenericValue> relations = PartyRelationshipHelper.getActivePartyRelationships(delegator, partyRelationshipValues);
			if (UtilValidate.isEmpty(relations)) {
				try {
					// 保证 客户partyId 具有 customer角色
					Map<String, Object> ensurePartyRoleOut = dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("partyId", customerPartyId, "roleTypeId", "CUSTOMER"));
					if(ServiceUtil.isError(ensurePartyRoleOut)){
						return ensurePartyRoleOut;
					}
					// 创建 客户 与 商家 的关系
					Map<String, Object> relationOutMap = dispatcher.runSync("createPartyRelationship", partyRelationshipValues);
					if (ServiceUtil.isError(relationOutMap)) {
						return relationOutMap;
					}
				} catch (GenericServiceException e) {
					Debug.logError(e, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
		Locale locale = (Locale) context.get("locale");
		
		String cardNumber = (String) context.get("cardNumber");
		String customerPartyId = (String) context.get("customerPartyId");
		String finAccountId = (String) context.get("finAccountId");
		String description = (String) context.get("description");
		Timestamp fromDate =(Timestamp)context.get("fromDate");
		if(UtilValidate.isEmpty(fromDate)){
			//FIXME 跟授权中那个前面用当前时间创建记录，随后去查询当前是“有效”的记录却查不到，一样的bug，
			// 而且用debug方式调试程序就不会出现这个bug，怪哉！
			// 临时解决方案，将开始时间人为提前2秒
			fromDate = UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2);
		}
		
		Timestamp thruDate =(Timestamp)context.get("thruDate");

		// 授权时，普通用户并没有为别的用户创建giftCard的权限，所以使用system用户来调用后面的服务
		GenericValue systemUser = (GenericValue) context.get("systemUser");
		if(null == systemUser){
			try {
				systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
		}
		
		// 创建giftCard
		Map<String, Object> giftCardMap = FastMap.newInstance();
		giftCardMap.put("userLogin", systemUser);
		giftCardMap.put("locale", locale); 
		giftCardMap.put("partyId", customerPartyId); 
		giftCardMap.put("description", description); 
		giftCardMap.put("cardNumber", cardNumber);
		giftCardMap.put("fromDate", fromDate);
		giftCardMap.put("thruDate", thruDate);
		Map<String, Object> giftCardOutMap;
		try {
			giftCardOutMap = dispatcher.runSync("createGiftCard", giftCardMap);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}
		
		// 关联 paymentMethod 与 finAccount
		String paymentMethodId = (String) giftCardOutMap.get("paymentMethodId");
		try {
			GenericValue paymentMethod = delegator.findByPrimaryKey("PaymentMethod", UtilMisc.toMap("paymentMethodId", paymentMethodId));
			paymentMethod.set("finAccountId", finAccountId);
			paymentMethod.store();
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		retMap.put("paymentMethodId", paymentMethodId);
		return retMap;
	}
	
	/**
	 * 获取商家用于扣减开卡限额的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @return
	 */
	public static GenericValue getCreditLimitAccount(Delegator delegator,  String organizationPartyId){
		return getCreditLimitAccount(delegator, organizationPartyId, false);
	}
	
	
	/**
	 * 获取商家用于扣减开卡限额的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @param useCache
	 * @return
	 */
	public static GenericValue getCreditLimitAccount(Delegator delegator,  String organizationPartyId, boolean useCache) {
		return getPartyGroupFinAccount(delegator, organizationPartyId, "SVCCRED_ACCOUNT", useCache);
	}
	
	/**
	 * 获取商家用于收款的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @return
	 */
	public static GenericValue getReceiptAccount(Delegator delegator,  String organizationPartyId) {
		return getReceiptAccount(delegator, organizationPartyId, false);
	}

	/**
	 * 获取商家用于收款的金融账户
	 * @param delegator
	 * @param organizationPartyId 商家partyId
	 * @param useCache
	 * @return
	 */
	public static GenericValue getReceiptAccount(Delegator delegator,  String organizationPartyId, boolean useCache) {
		return getPartyGroupFinAccount(delegator, organizationPartyId, "DEPOSIT_ACCOUNT", useCache);
	}

	/**
	 * 获取商家用于平台对账结算的金融账户
	 * @param delegator
	 * @param organizationPartyId
	 * @return
	 */
	public static GenericValue getSettlementAccount(Delegator delegator,  String organizationPartyId) {
		return getSettlementAccount(delegator, organizationPartyId,  false);
	}

	/**
	 * 获取商家用于平台对账结算的金融账户
	 * @param delegator
	 * @param organizationPartyId
	 * @param useCache
	 * @return
	 */
	public static GenericValue getSettlementAccount(Delegator delegator,  String organizationPartyId,  boolean useCache) {
		return getPartyGroupFinAccount(delegator, organizationPartyId, "BANK_ACCOUNT", useCache);
	}

	/**
	 * 根据organizationPartyId和 finAccountTypeId获取商家的金融账户
	 * @param delegator
	 * @param organizationPartyId
	 * @param finAccountTypeId
	 * @param useCache
	 * @return
	 */
	private static GenericValue getPartyGroupFinAccount(Delegator delegator, String organizationPartyId, String finAccountTypeId,
			boolean useCache) {
		EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("organizationPartyId", PLATFORM_PARTY_ID,
				"ownerPartyId", organizationPartyId, "finAccountTypeId", finAccountTypeId, "statusId", "FNACT_ACTIVE"));
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
	 * @return CloudCardInfo
	 * @throws GenericEntityException
	 */
	 public static GenericValue getCloudCardByCardCode(String cardCode, Delegator delegator) throws GenericEntityException {
		 return getCloudCardByCardCode(cardCode, true, delegator);
	 }
	
	
    /**
     * 根据二维码查询卡信息
     * @param cardCode 二维码信息
     * @param filterByDate 传入true时，不查找已过期的卡
     * @param delegator
     * @return CloudCardInfo
     * @throws GenericEntityException
     */
    public static GenericValue getCloudCardByCardCode(String cardCode, boolean filterByDate, Delegator delegator) throws GenericEntityException {
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
        List<GenericValue> giftCards = delegator.findByAnd("CloudCardInfo", UtilMisc.toMap("cardNumber", encryptedCardNumber));
        if(filterByDate){
        	giftCards =  EntityUtil.filterByDate(giftCards);
        }
        
        if (UtilValidate.isEmpty(giftCards)) {
        	//扫描的物理卡 去FinAccount里面找
        	GenericValue encryptedFinAccount = delegator.makeValue("FinAccount", UtilMisc.toMap("finAccountCode",cardCode));
            delegator.encryptFields(encryptedFinAccount);
            String encryptedFinAccountCode = encryptedFinAccount.getString("finAccountCode");
            EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("finAccountCode", encryptedFinAccountCode));
            if(filterByDate){
            	cond = EntityCondition.makeCondition(cond, EntityUtil.getFilterByDateExpr());
            }
            
            // 既然是物理卡，fromDate应该是最小的吧
            List<GenericValue> accounts = delegator.findList("CloudCardInfo", cond, null, UtilMisc.toList("fromDate"), null, false);
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
     * @param cardId 云卡的卡id （paymentMethodId）
     * @param delegator
     * @return CloudCardInfo
     * @throws GenericEntityException
     */
    public static GenericValue getCloudCardByCardId(String cardId, Delegator delegator) throws GenericEntityException {
    	if (UtilValidate.isEmpty(cardId)) {
    		return null;
    	}
    	return EntityUtil.getFirst( EntityUtil.filterByDate(delegator.findByAnd("CloudCardInfo", UtilMisc.toMap("paymentMethodId", cardId))));
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
	 * 获取账户授权信息
	 * 通过查询FinAccountRole中 有效期内的， 且 roleTypeId=SHAREHOLDER 的记录，来判断此卡当前是否处于被授权其他用户使用的状态
	 * @param cloudCard
	 * @param delegator
	 * @return
	 */
	public static Map<String, Object> getCardAuthorizeInfo(GenericValue cloudCard, Delegator delegator) {
		Map<String,Object> retMap = UtilMisc.toMap("isAuthorized", false);
		if (null == cloudCard){
			return retMap;
		}
		String finAccountId = cloudCard.getString("finAccountId");
		// TODO
		// 不能使用 EntityUtil.getFilterByDateExpr()创建时间条件
		// 因为授权这个服务传入的fromDate 可能是“未来”的某一个时间，
		// 就意味着 存在这样的情况:
		//	授权 这个服务调用完成后 一段时间内(直到授权fromDate前) 使用getCardAuthorizeInfo方法仍然得到此卡是未授权状态的错误结论，并能够再次授权
		// 这与授权后的卡不能再授权的规则矛盾
		//EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition dateCond =  EntityCondition.makeCondition(
			EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
			EntityOperator.OR,
			EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp())
		);
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("finAccountId", finAccountId, "roleTypeId", "SHAREHOLDER"));
		try {
			GenericValue authInfo =EntityUtil.getFirst(delegator.findList("FinAccountRole",
					EntityCondition.makeCondition(cond, dateCond), null, null, null, false));
			if (null != authInfo) {
				retMap.put("isAuthorized", true);
				retMap.put("fromDate", authInfo.getTimestamp("fromDate")); // 授权开始时间
				retMap.put("thruDate", authInfo.getTimestamp("thruDate")); // 授权结束时间
				retMap.put("toPartyId", authInfo.getString("partyId")); // 授权给谁了
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		return retMap;
	}
	
	/**
	 * 获取卡授权金额
	 *   建议用 getCloudCardBalance 方法获取卡余额，包括授权卡与被授权的卡
	 * @param finAccountId
	 * @param delegator
	 * @return
	 * @throws GenericEntityException
	 * @see {@link CloudCardHelper#getCloudCardBalance(GenericValue, boolean)}
	 */
	public static BigDecimal getCloudCardAuthBalance(String finAccountId, Delegator delegator){
		// find sum of all authorizations which are not expired
		EntityCondition authorizationConditions = EntityCondition.makeCondition(
				EntityCondition.makeCondition("finAccountId", finAccountId), EntityUtil.getFilterByDateExpr());
		GenericValue authSum = null;
		try {
			authSum = EntityUtil.getFirst(delegator.findList("FinAccountAuthSum", authorizationConditions,
					UtilMisc.toSet("amount"), null, null, false));
		} catch (GenericEntityException e) {
			Debug.logError("get cloud card auth amount error: " + e.getMessage(), module);
		}
		BigDecimal authorizationsTotal = null;
		if (null != authSum) {
			authorizationsTotal = authSum.getBigDecimal("amount");
		}
		if (null == authorizationsTotal) {
			authorizationsTotal = ZERO;
		}
		return authorizationsTotal.setScale(decimals, rounding);
	}
	
	
	/**
	 * 获取云卡可用余额
	 * <p>如果授权给别人了，取自己的availableBalance，否则取actualBalance</p>
	 * <pre>
	 * * 为什么不一直取 availableBalance？
	 *     因为卡授权给别人到期后 availableBalance不会自动恢复，除非有finAccountAuth的修改触发了计算更新availableBalance的ECA，
	 *     所以干脆每次查看余额的时候都去判断是否此卡被授权，没有授权就取actualBalance，有授权取 availableBalance
	 * </pre>
	 * <p>如果此卡是别人授权给我的，取授权金额，在授权未过期的情况下，授权金额余额可以用 actualBalance.subtract(availableBalance)得到</p>
	 * @param finAccountId
	 * @param isAuthorized
	 * @return
	 * @throws GenericEntityException
	 */
	public static BigDecimal getCloudCardBalance(GenericValue cloudCard, boolean isAuthorized){

		if(null == cloudCard) return CloudCardHelper.ZERO;

		BigDecimal actualBalance = cloudCard.getBigDecimal("actualBalance");
		BigDecimal availableBalance = cloudCard.getBigDecimal("availableBalance");
		if(null == actualBalance) actualBalance = CloudCardHelper.ZERO;
		if(null == availableBalance) availableBalance = CloudCardHelper.ZERO;
		
		BigDecimal cardBalance = actualBalance;
		
		String cardCode = cloudCard.getString("cardNumber");
		if(null == cardCode) cardCode = cloudCard.getString("finAccountCode");
		boolean isAuthToMe = null!=cardCode && cardCode.startsWith(CloudCardHelper.AUTH_CARD_CODE_PREFIX);
		
		if(isAuthToMe){
			// 如果是别人授权给我的卡，获取授权余额
			//cardBalance = CloudCardHelper.getCloudCardAuthBalance(cloudCard.getString("finAccountId"), delegator); 
			Timestamp thruDate = cloudCard.getTimestamp("thruDate");
			if(null != thruDate && UtilDateTime.nowTimestamp().after(thruDate)){
				// 已经过期的授权卡
				Debug.logInfo("cardId:["+cloudCard.getString("paymentMethodId")+"] thruDate:["+thruDate+"], this card has expired... cardBalance return Zero", module);
				cardBalance = ZERO; 
			}else{
				cardBalance = actualBalance.subtract(availableBalance).setScale(decimals, rounding); 
			}
		}else{
			if(isAuthorized){
				cardBalance = availableBalance;
			}
		}
		
		return cardBalance;
	}

	public static BigDecimal getCloudCardBalance(GenericValue cloudCard){

		if(null == cloudCard) return CloudCardHelper.ZERO;

		Delegator delegator = cloudCard.getDelegator();
		Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
		boolean isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
		return getCloudCardBalance(cloudCard, isAuthorized);
	}
	
	
	/**
	 * 从 PartyIdentification 获取店家的二维码，如果没有则生成一个
	 * @param organizationPartyId 店家partyId
	 * @param delegator
	 * @return 店家二维码
	 * @throws GenericEntityException
	 */
	public static String getOrGeneratePartyGroupQRcode(String organizationPartyId, Delegator delegator) throws GenericEntityException{

		String qrCodeStr = null;
		Map<String, Object> lookupFields = FastMap.newInstance();
		lookupFields.put("partyId", organizationPartyId);
		lookupFields.put("partyIdentificationTypeId", "STORE_QR_CODE");
		GenericValue partyIdentification = delegator.findByPrimaryKey("PartyIdentification", lookupFields);
		if(UtilValidate.isEmpty(partyIdentification)){
			qrCodeStr = STORE_QR_CODE_PREFIX + UUID.randomUUID().toString();
			lookupFields.put("idValue", qrCodeStr);
			delegator.makeValue("PartyIdentification", lookupFields).create();
		}else{
			qrCodeStr = partyIdentification.getString("idValue");
		}
		return qrCodeStr;
	}
	
	/**
	 * 从 PartyIdentification 获取店家的二维码，如果没有则生成一个
	 * @param organizationPartyId 店家partyId
	 * @param delegator
	 * @return 店家二维码，若发生异常则返回null
	 */
	public static String getOrGeneratePartyGroupQRcodeNoException(String organizationPartyId, Delegator delegator){
		String qrCodeStr = null;
		try {
			qrCodeStr = getOrGeneratePartyGroupQRcode(organizationPartyId, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		return qrCodeStr;
	}
	
	/**
	 * 根据商家二维码获取商家partyGroup实体
	 * @param qrCode 商家二维码
	 * @param delegator
	 * @return 返回对应的商家实体，若未找到返回null
	 * @throws GenericEntityException
	 */
	public static GenericValue getPartyGroupByQRcode(String qrCode, Delegator delegator) throws GenericEntityException{
		GenericValue partyGroup = null;
		Map<String, Object> lookupFields = FastMap.newInstance();
		lookupFields.put("idValue", qrCode);
		lookupFields.put("partyIdentificationTypeId", "STORE_QR_CODE");
		GenericValue partyIdentification = EntityUtil.getFirst(delegator.findByAnd("PartyIdentification", lookupFields));
		if(UtilValidate.isNotEmpty(partyIdentification)){
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", partyIdentification.getString("partyId")));
		}
		return partyGroup;
	}

	/**
	 * 根据商家二维码获取商家partyGroup实体
	 * @param qrCode 商家二维码
	 * @param delegator
	 * @return 返回对应的商家实体，若未找到或发生异常都返回null
	 */
	public static GenericValue getPartyGroupByQRcodeNoException(String qrCode, Delegator delegator){
		GenericValue partyGroup = null;
		try {
			partyGroup = getPartyGroupByQRcode(qrCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		return partyGroup;
	}

	/**
	 * 通过手机号码查询用户
	 * @param delegator
	 * @param teleNumber
	 * @return 返回的是TelecomNumberAndUserLogin实体，其中包含 partyId 和 userLoginId
	 * @throws GenericEntityException
	 */
	public static GenericValue getUserByTeleNumber(Delegator delegator, String teleNumber)
			throws GenericEntityException {
		return EntityUtil.getFirst(delegator.findList("TelecomNumberAndUserLogin", 
				EntityCondition.makeCondition(
						EntityCondition.makeCondition(UtilMisc.toMap("contactNumber", teleNumber)), 
						EntityUtil.getFilterByDateExpr()), null, UtilMisc.toList("partyId DESC"), null, false));
	}
}
