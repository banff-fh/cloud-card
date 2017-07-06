package com.banfftech.cloudcard.lbs;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.ofbiz.base.util.UtilDateTime;

import com.alibaba.fastjson.JSONObject;
import com.ibm.icu.util.Calendar;


public class BaiduLBSUtilTest {

	private static String ak = "lfDGGxskR8B9SvTlM768WrI5";
	private static String geotable_id = "161504";
	
	private static BaiduLBSUtil baiduLBSUtil = new BaiduLBSUtil();
	public static String getAk() {
		return ak;
	}
	public static void setAk(String ak) {
		BaiduLBSUtilTest.ak = ak;
	}
	public static String getGeotable_id() {
		return geotable_id;
	}
	public static void setGeotable_id(String geotableId) {
		geotable_id = geotableId;
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void createGeotable(){
		Map params = new HashMap();
		params.put("name", "around");
		params.put("geotype", "1");
		params.put("is_published", "1");
		params.put("ak", ak);
		System.out.println(baiduLBSUtil.createGeotable(params));
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void listGeotable(){
		Map params = new HashMap();
		params.put("ak", ak);
		System.out.println(baiduLBSUtil.listGeotable(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void detailGeotable(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("id", geotable_id);
		System.out.println(baiduLBSUtil.detailGeotable(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void updateGeotable(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("id", "1975996120");
		System.out.println(baiduLBSUtil.updateGeotable(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void deleteGeotable(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("id", "");
		System.out.println(baiduLBSUtil.deleteGeotable(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void createColumn1(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		params.put("name", "城市");
		params.put("key", "city");
		params.put("type", "3");
		params.put("max_length", "512");
		//是否排序字段
		params.put("is_sortfilter_field", "0");
		//是否查询字段
		params.put("is_search_field", "1");
		//是否索引字段
		params.put("is_index_field", "1");
		params.put("is_unique_field ", "0");
		System.out.println(baiduLBSUtil.createColumn(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void createColumn2(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		params.put("name", "地区");
		params.put("key", "district");
		params.put("type", "3");
		params.put("max_length", "512");
		//是否排序字段
		params.put("is_sortfilter_field", "0");
		//是否查询字段
		params.put("is_search_field", "1");
		//是否索引字段
		params.put("is_index_field", "1");
		params.put("is_unique_field ", "0");
		System.out.println(baiduLBSUtil.createColumn(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void createColumn3(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		params.put("name", "公司ID");
		params.put("key", "company_id");
		params.put("type", "1");
		params.put("max_length", "512");
		//是否排序字段
		params.put("is_sortfilter_field", "1");
		//是否查询字段
		params.put("is_search_field", "0");
		//是否索引字段
		params.put("is_index_field", "1");
		params.put("is_unique_field ", "0");
		System.out.println(baiduLBSUtil.createColumn(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void listColumn(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		System.out.println(baiduLBSUtil.listColumn(params));
	}
	@SuppressWarnings("unchecked")
	@Test
	public void detailColumn() {
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		params.put("id", "1975996120");
		System.out.println(baiduLBSUtil.detailColumn(params));
	}
	/*
	 * 创建位置信息
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void createPOI(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		params.put("title", "大蓉和酒楼");
		params.put("address", "成都市双流县海昌路99号");
		//纬度
		params.put("latitude", "30.497256");
		//经度
		params.put("longitude", "104.080983");
		params.put("tags", "酒店");
		params.put("coord_type", "3");
		//自定义列
		params.put("storeId", 10560);
		
		System.out.println(baiduLBSUtil.createPOI(params));
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void listPOI(){
		Map params = new HashMap();
		params.put("ak", ak);
		params.put("geotable_id",geotable_id);
		System.out.println(baiduLBSUtil.listPOI(params));
	}
	@Test
	public void deletePOI(){
		Map<String ,String > params = new HashMap<String, String>();
		params.put("ak", ak);
		params.put("geotable_id", geotable_id);
		System.out.println(baiduLBSUtil.deletePOI(params));
	}
	@Test
	public void nearby(){
		Map<String ,String > params = new HashMap<String, String>();
		params.put("ak", ak);
		params.put("geotable_id", geotable_id);
		params.put("q", "");
		params.put("location", "120.178916,30.32744");
		params.put("Coord_type", "3");
		
		JSONObject a = JSONObject.parseObject(baiduLBSUtil.nearby(params));
		System.out.println(a);

	}
	@Test
	public void Local(){
		Map<String ,String > params = new HashMap<String, String>();
		params.put("ak", ak);
		params.put("geotable_id", geotable_id);
		params.put("q", "");
		params.put("Coord_type", "3");
		params.put("location", "120.178916,30.32744");
    	//params.put("region", "宁波市");
    	params.put("region", "成都市");
    	//params.put("filter", "storeId:[10435,10436]");

		JSONObject a = JSONObject.parseObject(baiduLBSUtil.local(params));
		System.out.println(a);

	}
	
	@Test
	public void geocoder(){
		Map<String ,String > params = new HashMap<String, String>();
		params.put("ak", ak);
    	params.put("location", "30.32422881426311,120.16462465982332");
    	params.put("output", "json");
    	params.put("callback", "showLocation");

		//System.out.println(baiduLBSUtil.geocoder(params));
    	String geocoder = BaiduLBSUtil.geocoder(params);
    	geocoder = geocoder.replace("showLocation&&showLocation(", "");
    	geocoder = geocoder.replace(")", "");
    	//JSONObject addrJSONObject = ;
    	//JSONObject resultJSONObject = ;
    	JSONObject addressJSONObject = JSONObject.parseObject(JSONObject.parseObject(JSONObject.parseObject(geocoder).getString("result")).getString("addressComponent"));
    	System.out.println(addressJSONObject.get("district"));
	}
	
	public void toSqlTime(){
		System.out.println(UtilDateTime.toDate("2017-04-13 10:38:18.0"));
	}
	
	@Test
	public void timeTest(){
		Calendar calendar = UtilDateTime.toCalendar(UtilDateTime.nowTimestamp());
		 System.out.println(calendar.get(Calendar.DAY_OF_WEEK)-1);
		 System.out.println(calendar.get(Calendar.HOUR_OF_DAY));
	}
}
