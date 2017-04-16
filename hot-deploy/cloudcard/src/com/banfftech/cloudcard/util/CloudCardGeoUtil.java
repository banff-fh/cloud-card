package com.banfftech.cloudcard.util;

import java.util.List;

import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;

import javolution.util.FastList;

/**
 * 库胖卡地理信息相关帮助类
 * 
 * @author cy
 *
 */
public class CloudCardGeoUtil {

    public static final String module = CloudCardGeoUtil.class.getName();

    /**
     * Returns Geo级联的下级Geo信息
     * 
     * @throws GenericEntityException
     */
    public static List<GenericValue> getAssociatedGeoList(Delegator delegator, String geoIdFrom, String geoAssocTypeId, String listOrderBy)
            throws GenericEntityException {
        List<GenericValue> geoList = FastList.newInstance();
        if (UtilValidate.isEmpty(geoIdFrom)) {
            return geoList;
        }

        if (UtilValidate.isEmpty(listOrderBy)) {
            listOrderBy = "geoId";
        }
        if (UtilValidate.isEmpty(geoAssocTypeId)) {
            geoAssocTypeId = "REGIONS";
        }
        List<String> sortList = UtilMisc.toList(listOrderBy);

        // get all related states
        EntityCondition stateProvinceFindCond = EntityCondition.makeCondition(EntityCondition.makeCondition("geoIdFrom", geoIdFrom),
                EntityCondition.makeCondition("geoAssocTypeId", geoAssocTypeId));
        geoList.addAll(delegator.findList("GeoAssocAndGeoTo", stateProvinceFindCond, null, sortList, null, true));

        return geoList;
    }
}
