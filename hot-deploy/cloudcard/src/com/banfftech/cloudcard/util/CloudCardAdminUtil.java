package com.banfftech.cloudcard.util;

import java.util.List;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;

import javolution.util.FastList;

/**
 * 库胖卡后台页面帮助类
 * 
 * @author cy
 *
 */
public class CloudCardAdminUtil {

    public static final String module = CloudCardAdminUtil.class.getName();

    
    
    /**
     * 获取商家的店长id列表
     * 
     * @param delegator
     * @param userLogin
     * @param organizationPartyId
     */
    public static List<String> getStoreOwnerPartyIds(Delegator delegator, String partyId) {
        List<EntityCondition> condList = FastList.newInstance();
        condList.add(EntityCondition.makeCondition("partyIdFrom", partyId));
        condList.add(EntityCondition.makeCondition("roleTypeIdTo", "MANAGER"));
        condList.add(EntityCondition.makeCondition("roleTypeIdFrom", "INTERNAL_ORGANIZATIO"));
        condList.add(EntityCondition.makeCondition("partyRelationshipTypeId", "EMPLOYMENT"));
        condList.add(EntityUtil.getFilterByDateExpr());
        EntityCondition condition = EntityCondition.makeCondition(condList);

        List<GenericValue> partyRelationships = null;
        try {
            partyRelationships = delegator.findList("PartyRelationship", condition, null, UtilMisc.toList("fromDate"), null, false);
        } catch (GenericEntityException e) {
            Debug.logError(e, "Problem finding PartyRelationships. ", module);
            return null;
        }
        if (UtilValidate.isEmpty(partyRelationships)) {
            return null;
        }
        return EntityUtil.getFieldListFromEntityList(partyRelationships, "partyIdTo", true);
    }
}
