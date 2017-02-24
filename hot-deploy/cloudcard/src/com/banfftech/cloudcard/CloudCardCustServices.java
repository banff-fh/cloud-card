package com.banfftech.cloudcard;

import java.util.List;
import java.util.Map;

import org.ofbiz.service.DispatchContext;
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
		String longitude = (String) context.get("longitude");
		String latitude = (String) context.get("latitude");
		String radius = (String) context.get("radius");
		
		Map<String, Object> params = FastMap.newInstance();
		params.put("ak", "lfDGGxskR8B9SvTlM768WrI5");
		params.put("geotable_id", "161504");
		params.put("q", "");
		params.put("Coord_type", "3");
		params.put("location", longitude+","+latitude);
		params.put("radius", "1000");
		
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
				storeList.add(storeMap);
			}
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("listSize", lbsResult.get("total").toString());
		result.put("storeList", storeList);
		return result;
	}
}
