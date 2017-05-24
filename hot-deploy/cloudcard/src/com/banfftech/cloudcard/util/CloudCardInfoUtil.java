package com.banfftech.cloudcard.util;

import java.sql.Timestamp;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;

import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.constant.CloudCardConstant;

import javolution.util.FastMap;

/**
 * 库胖卡信息相关帮助类
 * 
 * @author cy
 *
 */
public class CloudCardInfoUtil {

    public static final String module = CloudCardInfoUtil.class.getName(); 

    /**
     * 构造 查询 某人 在 某店 可以使用的 卡的 查询条件
     * 
     * @param delegator
     *            实体引擎
     * @param partyId
     *            某人
     * @param storeId
     *            某店
     * @return 返回EntityCondition对象
     * @throws GenericEntityException
     */
    public static EntityCondition createLookupMyStoreCardCondition(Delegator delegator, String partyId, String storeId) throws GenericEntityException {
        EntityCondition lookupConditions = EntityCondition.makeCondition(UtilMisc.toMap("partyId", partyId, "statusId", "FNACT_ACTIVE"));
        if (UtilValidate.isNotEmpty(storeId)) {
            EntityCondition storeCond = EntityCondition.makeCondition("distributorPartyId", storeId);
            String groupOwnerId = CloudCardHelper.getGroupOwneIdByStoreId(delegator, storeId, true);
            if (null != groupOwnerId && !groupOwnerId.equals(storeId)) {
                // 存在圈主，且不是自己的情况，
                storeCond = EntityCondition.makeCondition(EntityOperator.OR, storeCond, EntityCondition.makeCondition("distributorPartyId", groupOwnerId));
            }
            
            lookupConditions = EntityCondition.makeCondition(lookupConditions, storeCond);
        }
        lookupConditions = EntityCondition.makeCondition(lookupConditions, EntityUtil.getFilterByDateExpr());
        return lookupConditions;
    }

    /**
     * 将 CloudCardInfo视图的查询结果封装成 Map， 并处理 卡号、卡名、余额、是否授权、授权起止时间 等信息
     * 
     * @param delegator
     * @param partyId
     * @param cloudCard
     * @return
     */
    public static Map<String, Object> packageCloudCardInfo(Delegator delegator, GenericValue cloudCard) {
        Map<String, Object> cloudCardMap = FastMap.newInstance();
        String distributorPartyId = cloudCard.getString("distributorPartyId");
        String partyId = cloudCard.getString("partyId");
        if (distributorPartyId != null) {
            // 图片地址
            cloudCardMap.put("cardImg", EntityUtilProperties.getPropertyValue("cloudcard", "cardImg." + distributorPartyId, delegator));
        }
        String cardName = UtilFormatOut.checkEmpty(cloudCard.getString("description"), cloudCard.getString("finAccountName"));
        String authThruDate = "";
        cloudCardMap.put("cardName", cardName); // 卡名
        String cardCode = cloudCard.getString("cardNumber");
        cloudCardMap.put("cardCode", cardCode); // 卡二维码
        cloudCardMap.put("cardId", cloudCard.get("paymentMethodId"));// 卡id

        boolean isAuthorized = false;
        if (cardCode.startsWith(CloudCardConstant.AUTH_CARD_CODE_PREFIX)) {
            // 如果是别人授权给我的卡，显示授权金额的余额
            cloudCardMap.put("isAuthToMe", "Y"); // 已授权给我
            cloudCardMap.put("isAuthToOthers", "N"); // 已授权给别人
            cloudCardMap.put("authFromDate", cloudCard.getTimestamp("fromDate").toString()); // 授权开始时间
            if (UtilValidate.isNotEmpty(cloudCard.getTimestamp("thruDate"))) {
                authThruDate = cloudCard.getTimestamp("thruDate").toString();
            }
            cloudCardMap.put("authThruDate", authThruDate); // 授权结束时间
            cloudCardMap.put("authFromPartyId", cloudCard.get("ownerPartyId")); // 谁授权
            cloudCardMap.put("authToPartyId", partyId); // 授权给谁
        } else {
            // 账户可用余额
            // 如果是已经授权给别人的卡，展示授权开始、结束时间，以及授权给谁
            Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
            isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
            if (isAuthorized) {
                cloudCardMap.put("isAuthToMe", "N"); // 已授权给我
                cloudCardMap.put("isAuthToOthers", "Y"); // 已授权给别人
                cloudCardMap.put("authFromDate", ((Timestamp) cardAuthorizeInfo.get("fromDate")).toString()); // 授权开始时间
                if (UtilValidate.isNotEmpty(cardAuthorizeInfo.get("thruDate"))) {
                    authThruDate = ((Timestamp) cardAuthorizeInfo.get("thruDate")).toString();
                }
                cloudCardMap.put("authThruDate", authThruDate); // 授权结束时间
                cloudCardMap.put("authFromPartyId", partyId); // 谁授权
                cloudCardMap.put("authToPartyId", cardAuthorizeInfo.get("toPartyId")); // 授权给谁
            } else {
                cloudCardMap.put("isAuthToMe", "N"); // 已授权给我
                cloudCardMap.put("isAuthToOthers", "N"); // 已授权给别人
            }
        }
        cloudCardMap.put("cardBalance", CloudCardHelper.getCloudCardBalance(cloudCard, isAuthorized));
        cloudCardMap.put("distributorPartyId", distributorPartyId); // 发卡商家partyId
        // 卡主
        cloudCardMap.put("ownerPartyId", cloudCard.get("ownerPartyId"));
        
        // 店家信用等级
        EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
        EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("partyId", distributorPartyId,"partyClassificationTypeId","STORE_LEVEL_CLASSIFI"));
        GenericValue partyClassification;
		try {
			partyClassification = EntityUtil.getFirst(
			        delegator.findList("PartyClassificationAndPartyClassificationGroup", EntityCondition.makeCondition(cond, dateCond), null, UtilMisc.toList("-fromDate"), null, true));
			if (null == partyClassification) {
	            // 如果没有，则返回最高级
	            Map<String, Object> partyClassificationMap = FastMap.newInstance();
	            partyClassificationMap.put("partyId", distributorPartyId);
	            partyClassificationMap.put("partyClassificationGroupId", "STORE_LEVEL_5");
	            partyClassificationMap.put("fromDate", UtilDateTime.nowTimestamp());
	            partyClassification = delegator.makeValue("PartyClassification", partyClassificationMap);
	            partyClassification.create();
	        }
			cloudCardMap.put("storeLevel", partyClassification.getString("partyClassificationGroupId"));
		} catch (GenericEntityException e) {
			 Debug.logError(e.getMessage(), module);
		}
		
		// 获取店铺联系方式
        Map<String, Object> geoAndContactMechInfoMap = CloudCardHelper.getGeoAndContactMechInfoByStoreId(delegator, null, distributorPartyId);
        if (UtilValidate.isNotEmpty(geoAndContactMechInfoMap)) {
            cloudCardMap.put("storeAddress", geoAndContactMechInfoMap.get("storeAddress"));
            cloudCardMap.put("storeTeleNumber", geoAndContactMechInfoMap.get("storeTeleNumber"));
        }

        return cloudCardMap;
    }
}
