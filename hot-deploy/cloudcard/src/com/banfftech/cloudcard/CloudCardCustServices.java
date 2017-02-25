package com.banfftech.cloudcard;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.banfftech.cloudcard.lbs.BaiduLBSUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

public class CloudCardCustServices {
	public static final String module = CloudCardCustServices.class.getName();

	/**
	 * 附近的店铺
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userStoreListLBS(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String longitude = (String) context.get("longitude");
		String latitude = (String) context.get("latitude");

		String ak = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.ak", delegator);
		String getTableId = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.getTableId", delegator);
		String radius = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.radius", delegator);
		String CoordType = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.CoordType", delegator);
		String q = EntityUtilProperties.getPropertyValue("cloudcard", "baiduMap.q", delegator);

		Map<String, Object> params = FastMap.newInstance();
		params.put("ak", ak);
		params.put("geotable_id", getTableId);
		params.put("q", q);
		params.put("Coord_type", CoordType);
		params.put("location", longitude + "," + latitude);
		params.put("radius", radius);
		
		List<Map<String,Object>> storeList = FastList.newInstance();
		JSONObject lbsResult = JSONObject.parseObject(BaiduLBSUtil.nearby(params));
		if(!"0".equalsIgnoreCase(lbsResult.get("total").toString())){
			JSONArray jsonArray = JSONObject.parseArray(lbsResult.get("contents").toString());
			for(int i = 0 ;i<jsonArray.size();i++){
				Map<String, Object> storeMap = FastMap.newInstance();
				storeMap.put("storeName",jsonArray.getJSONObject(i).getObject("storeName",String.class));
				storeMap.put("storeId",jsonArray.getJSONObject(i).getObject("storeId",String.class) );
				storeMap.put("isGroupOwner",jsonArray.getJSONObject(i).getObject("isGroupOwner",String.class) );
				storeMap.put("distance",jsonArray.getJSONObject(i).getObject("distance",String.class) );
				storeMap.put("location",jsonArray.getJSONObject(i).getObject("location",String.class) );
				storeList.add(storeMap);
			}
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("listSize", lbsResult.get("total").toString());
		result.put("storeList", storeList);
		return result;
	}
	
	/**
	 * 查看店铺简要信息
	 * 
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userGetStoreInfo(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
		String storeId = (String) context.get("storeId");
		String storeName = null;
		String storeImg = null;
		String storeAddress = null;
		String storeTeleNumber = null;
		String longitude = null;
		String latitude = null;
		
		
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeId", storeId);
		result.put("storeName", storeName);
		result.put("storeImg", storeImg);
		result.put("storeAddress", storeAddress);
		result.put("storeTeleNumber", storeTeleNumber);
		result.put("longitude", longitude);
		result.put("latitude", latitude);

		return result;
	}
}
