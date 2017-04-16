package com.banfftech.cloudcard;

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
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.util.CloudCardGeoUtil;

import javolution.util.FastList;

/**
 * 地理位置相关的服务
 * 
 * @author ChenYu
 *
 */
public class CloudCardGeoServices {

    public static final String module = CloudCardGeoServices.class.getName();
    public static final String resourceError = "cloudcardErrorUiLabels";

    /**
     * 获取关联的下级地理信息的服务
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> getAssociatedGeoList(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String geoIdFrom = (String) context.get("geoIdFrom");
        String geoAssocTypeId = (String) context.get("geoAssocTypeId");
        String listOrderBy = (String) context.get("listOrderBy");

        List<GenericValue> associatedGeoList = FastList.newInstance();
        try {
            associatedGeoList = CloudCardGeoUtil.getAssociatedGeoList(delegator, geoIdFrom, geoAssocTypeId, listOrderBy);
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        if (UtilValidate.isEmpty(associatedGeoList)) {
            associatedGeoList.add(delegator.makeValue("Geo", UtilMisc.toMap("geoId", "_NA_", "geoName", "不可用")));
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("geoList", associatedGeoList);
        return result;
    }

}
