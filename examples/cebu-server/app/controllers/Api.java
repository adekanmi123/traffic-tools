package controllers;

import play.*;
import play.db.jpa.JPA;
import play.mvc.*;
import utils.DateUtils;
import utils.DistanceCache;
import utils.EncodedPolylineBean;
import utils.Observation;
import utils.StreetVelocityCache;
import com.conveyal.traffic.graph.TrafficStats;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static akka.pattern.Patterns.ask;

import org.opengis.referencing.operation.MathTransform;
import org.opentripplanner.routing.algorithm.GenericAStar;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.impl.StreetVertexIndexServiceImpl;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.util.PolylineEncoder;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.geotools.geometry.jts.JTS;

import akka.dispatch.Future;
import akka.dispatch.OnSuccess;
import akka.event.Logging;
import api.AlertSimple;
import api.AuthResponse;
import api.MessageResponse;
import api.Path;
import com.conveyal.traffic.graph.TrafficStatsResponse;

import com.conveyal.traffic.graph.TrafficEdge;
import com.conveyal.traffic.graph.TrafficGraph;
import utils.GeoUtils;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import jobs.ObservationHandler;
import models.*;

public class Api extends Controller {

	static public Integer CURRENT_APP_VERSION = 1;
	
	public static DistanceCache distanceCache = new DistanceCache();
	
	public static TrafficGraph jakataGraph = new TrafficGraph(Play.configuration.getProperty("application.otpGraphPath") + "/jakarta", "jakarta");
	public static TrafficGraph manilaGraph = new TrafficGraph(Play.configuration.getProperty("application.otpGraphPath") + "/manila", "manila");
	public static TrafficGraph cebuGraph = new TrafficGraph(Play.configuration.getProperty("application.otpGraphPath") + "/cebu", "cebu");
	
	public static TrafficGraph getGraph(String graphId) {
		if(graphId.equals("jakarta"))
			return jakataGraph;
	
		if(graphId.equals("manila"))
			return manilaGraph;
		
		if(graphId.equals("cebu"))
			return cebuGraph;
		
		return cebuGraph;
	}
	
	public static ObjectMapper jsonMapper = new ObjectMapper();
		
	private static List<Long> ConvetStringArrayToLongArray(String[] stringArray){
		ArrayList<Long> longList = new ArrayList<Long>();

	for(String str : stringArray){	
	longList.add(new Long(str));
	}

	return longList;
	}

	public static void alerts(String imei, String type, String ids) {
	
		List<Alert> alerts = null;
		Boolean showPublicAlertsOnly = true;
		
		Date now = new Date();
		
		if(imei != null && !imei.isEmpty()){
			Phone phone = Phone.find("imei = ?", imei).first();
			if(phone != null && phone.operator != null && phone.operator.name.equals("CITOM")) {
				
				showPublicAlertsOnly = false;
			}
			
		}
		
		ArrayList<AlertSimple> data = new ArrayList<AlertSimple>();
		
		if(showPublicAlertsOnly){
			alerts = Alert.find("activeFrom <= ? and (activeTo is null or activeTo >= ?) and publiclyVisible = true", now, now).fetch();
			
			for(Alert alert : alerts)
			{
				data.add(new AlertSimple(alert, false));
			}
		}
		else {
		
			alerts = Alert.find("activeFrom <= ? and (activeTo is null or activeTo >= ?)", now, now).fetch();
		
			for(Alert alert : alerts)
			{
				data.add(new AlertSimple(alert, true));
			}
		}
			
		
		

		
		if(request.format == "xml")
			renderXml(data);
		else
			renderJSON(data);
	}
	

	
	
	public static void messages(String imei, Long message_id, Double lat, Double lon, String content) {
		Phone phone = Phone.find("imei = ?", imei).first();
		if(request.method == "POST")
		{
			
			if(phone != null)
			{
				Message message = new Message();
				
				// TODO message_id lookup for threading -- not useful for testing 
				
				message.read = false;
				
				message.fromPhone = phone;
				
				// TODO SimpleDateFormat df = new SimpleDateFormat("yyyy-mm-dd hh:MM:SS");
				
				message.timestamp = new Date();
				
				
				message.location_lat = lat;
				message.location_lon = lon;
				message.body = content;
				
				message.save();
			}
			else
			{
				Logger.info("Unknown phone entry for IMEI " + imei); 
				unauthorized("Unknown Phone IMEI");
			}
			
		}
		else
		{
			// TODO IMEI message lookup -- not useful for testing
		
			List<Message> messages = Message.find("toPhone = ? and read = false", phone).fetch();
			
			List<MessageResponse> messageResponses = new ArrayList<MessageResponse>();
			
			for(Message message : messages)
			{
				message.read = true;
				message.save();
				
				messageResponses.add(new MessageResponse(message));
			}
			
			// TODO mark messages read -- not useful for testing
		
			if(request.format == "xml")
				renderXml(messageResponses);
			else
				renderJSON(messageResponses);
		}
	}
	
	
	public static void panic(String imei, Boolean panic)
	{
	
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone != null)
		{
			phone.panic = panic;
			phone.save();
		}
		
		ok();
	}
	
	public static void registerGCM(String imei, String gcmKey)
	{
	
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone != null)
		{
			phone.gcmKey = gcmKey;
			phone.save();
		}
		
		ok();
	}
	
	public static void operator(String imei, String version)
	{
		Logger.info("Operator Auth request for IMEI " + imei); 
		
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone != null)
		{
			AuthResponse authResponse = new AuthResponse();
			
			authResponse.id = phone.id;
			authResponse.name = phone.operator.name;
			
			if(phone.driver != null)
			{
				authResponse.driverId = phone.driver.driverId;
				authResponse.driverName = phone.driver.name;
			}
			
			if(phone.vehicle != null)
			{
				authResponse.bodyNumber = phone.vehicle.bodyNumber;
			}
			
			authResponse.gpsInterval = 5;
			authResponse.updateInterval = 30;
			
			authResponse.appVersion = CURRENT_APP_VERSION;
			
			if(request.format == "xml")
				renderXml(authResponse);
			else
				renderJSON(authResponse);
		}
		else
		{
			Logger.info("Unknown phone entry for IMEI " + imei); 
			unauthorized("Unknown Phone IMEI");
		}
			
	}
	
	public static void register(String imei, String phoneNumber, Long operator)
	{
		if(imei != null && !imei.isEmpty() && operator != null)
		{
			Phone phone = Phone.find("imei = ?", imei).first();
			
			if(phone == null)
			{
				Logger.info("Creating phone entry for IMEI " + imei); 
				phone = new Phone();
				
				Operator operatorObj = Operator.findById(new Long(1));			
				phone.operator = operatorObj;
				phone.imei = imei;
				phone.phoneNumber = phoneNumber;				
	

			}
			
			Operator operatorObj = Operator.findById(operator);
			
			if(operatorObj == null)
			{
				Logger.info("Unknown operator: " + operator); 
				badRequest();
			}
			
			phone.operator = operatorObj;
			
			phone.save();
			
			ok();
		}
		else
		{
			List<Operator> operators = Operator.findAll();
			
			if(request.format == "xml")
				renderXml(operators);
			else
				renderJSON(operators);
		}
	}
	
	public static void login(String imei, String driver, String vehicle)
	{
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone == null)
		{
			//Logger.info("Unknown phone entry for IMEI " + imei); 
			//unauthorized("Unknown Phone IMEI");
			
			phone = new Phone();
			
			Operator operatorObj = Operator.findById(new Long(1));			
			phone.imei = imei;
			phone.operator = operatorObj;
			
			phone.save();
		}
		
		if(driver == null)
			badRequest();
		
		Driver driverObj = Driver.find("driverId = ?", driver).first();
		
		if(driverObj == null)
		{
			Logger.info("Unknown Driver Id " + driver); 
			
			driverObj = new Driver();
			driverObj.driverId = driver;
			driverObj.save();
		
		}
		
		if(vehicle == null)
			badRequest();
		
		Vehicle vehicleObj = Vehicle.find("bodyNumber = ?", vehicle).first();
		
		if(vehicleObj == null)
		{
			Logger.info("Unknown vehicle, createing record for body number " + vehicle); 
			
			vehicleObj = new Vehicle();
			vehicleObj.bodyNumber = vehicle;
			vehicleObj.save();
		}
		
		distanceCache.linkImeiToVehicle(imei, vehicleObj);
		
		phone.driver = driverObj;
		phone.vehicle = vehicleObj;
		
		phone.save();

		AuthResponse authResponse = new AuthResponse();
		
		authResponse.id = phone.id;
		authResponse.name = phone.operator.name;
		
		if(phone.driver != null)
		{
			authResponse.driverId = phone.driver.driverId;
			authResponse.driverName = phone.driver.name;
		}
		
		if(phone.vehicle != null)
		{
			authResponse.bodyNumber = phone.vehicle.bodyNumber;
		}
		
		authResponse.gpsInterval = 5;
		authResponse.updateInterval = 30;
		
		authResponse.appVersion = CURRENT_APP_VERSION;
		
		if(request.format == "xml")
			renderXml(authResponse);
		else
			renderJSON(authResponse);
	}
	
	public static void logout(String imei)
	{
		if(imei == null)
			unauthorized("IMEI Required");
		
		Phone phone = Phone.find("imei = ?", imei).first();
		
		if(phone == null)
		{
			Logger.info("Unknown phone entry for IMEI " + imei); 
			unauthorized("Unknown Phone IMEI");
		}
		
		phone.driver = null;
		phone.vehicle = null;
		
		phone.save();

		ok();
	}
	
	public static void locationPb(File data) {
	
		try {
			
			DataInputStream dataInputStream = new DataInputStream(new BufferedInputStream(new FileInputStream(data)));
			
			byte[] dataFrame = new byte[(int)data.length()];;
			dataInputStream.read(dataFrame);
			Ws.processPbFrame(dataFrame, "http " + request.headers.get("user-agent"));
			
			dataInputStream.close();
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			
			badRequest();
		}
		
		ok();
	}
	
	public static void sendTest() {
		DefaultHttpClient httpclient = new DefaultHttpClient();
    	HttpPost httpPost = new HttpPost("http://localhost:9001/application/sendData");
    	List <BasicNameValuePair> nvps = new ArrayList <BasicNameValuePair>();
    	nvps.add(new BasicNameValuePair("lat", new Double(10.0).toString()));
    	nvps.add(new BasicNameValuePair("lon", new Double(30.0).toString()));
    	nvps.add(new BasicNameValuePair("id", new Long(10).toString()));
    	nvps.add(new BasicNameValuePair("time", new Long(10).toString()));
 
    	try {
    		httpPost.setEntity(new UrlEncodedFormEntity(nvps));
			httpclient.execute(httpPost);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
    public static void location(String imei, String content, String timesent, Boolean charging, Double battery, Boolean boot, Boolean shutdown, Boolean failednetwork, Integer signal) throws IOException {
    
    	// test request via curl:
    	// 
    	// curl -d "20120430T133023,124.02342,34.43622,8.33,124,200" http://localhost:9000/api/location?imei=myIMEI    	
    	
		// check for valid request
    	
    	Date timeReceivedDate = new Date();
		
    	
    	if(imei == null || imei.trim().isEmpty())
    		badRequest();
    
   	
    	// copy POST body to string

    	String requestBody = null;
    	String message = "";
    	
		if(content != null)
		{
			requestBody = content;
			
		}
		else if(request.method == "POST")
        {
              requestBody = params.get("body");
        }
		
		String[] lines = requestBody.split("\n");
		
		message = "location message received: imei=" + imei + " " + content;
    	
		Date timeSentDate = null;
    	
		Long timeDelta = null;
		
    	try
    	{
    		timeSentDate = DateUtils.parseBasic(timesent.replace("T", " "));
   
    		timeDelta = timeReceivedDate.getTime() - timeSentDate.getTime();
    	}
    	catch(Exception e)
    	{
        	try
        	{
        		timeSentDate = new Date();
        		// failed to parse local time, must fall back to time received for last update
        		
            	String[] lineParts = lines[lines.length-1].trim().split(",");
        		
        		Date lastUpdateDate = DateUtils.parseLocationUpdate(lineParts[0].replace("T", " "));
        		
        		timeDelta = timeReceivedDate.getTime() - lastUpdateDate.getTime();
        	}
        	catch(Exception e1)
            {
        		// failed to parse last update timestamp, setting delta to 0
        		
        		timeDelta = new Long(0);
            }
    	}
    	
    	
    	
    	if(charging == null)
    		charging = false;
		
    	if(boot == null)
    		boot = false;
    	
    	if(shutdown == null)
    		shutdown = false;
    	
    	if(battery == null)
    		battery = -1.0;
    	
    	if(signal == null)
    		signal = -1;
    	
    	if(failednetwork == null)
    		failednetwork = false;
    	
    	if(requestBody == null || requestBody.isEmpty())
    	{
    		Logger.info("Empty location update received for ", imei);
    		
    		Calendar calendar = Calendar.getInstance();
    		calendar.setTimeInMillis(timeSentDate.getTime() + timeDelta);

    		Date adjustedDate = calendar.getTime();
    		
    		LocationUpdate.natveInsert(LocationUpdate.em(), imei, charging, battery, timeSentDate, adjustedDate, timeSentDate, timeReceivedDate, boot, shutdown, failednetwork, signal);
    		ok();
    	}	
    	
    	// requests can contain multiple requests, split on newline
    	
    	
    	 	
    	//VehicleUpdate update = new VehicleUpdate(imei);
    	
    	Observation observation = null;
    	
    	for(String line : lines)
    	{
    		// request format: 20120430T133023,124.02342,34.43622,8.33,124,200
    		
    		String[] lineParts = line.trim().split(",");
    		
    		
    		if(lineParts.length != 6)
    			badRequest();
    	
    		try
    		{
	    		Date dateTime = DateUtils.parseLocationUpdate(lineParts[0].replace("T", " "));	
	    		Calendar calendar = Calendar.getInstance();
	    		calendar.setTimeInMillis(dateTime.getTime() + timeDelta);

	    		Date adjustedDate = calendar.getTime();
	    	    
	    		Double lat = Double.parseDouble(lineParts[1]);
	    		Double lon = Double.parseDouble(lineParts[2]);
	    		Double velocity = Double.parseDouble(lineParts[3]);
	    		Double heading = Double.parseDouble(lineParts[4]);
	    		Double gpsError = Double.parseDouble(lineParts[5]);
	    		
	    		Coordinate locationCoord = new Coordinate(lon, lat);
	    		
	    		observation = new Observation(imei, adjustedDate, locationCoord , velocity, heading, gpsError);
	    		
	    		//update.addObservation(observation);
	    		
	    		distanceCache.updateDistance(imei, locationCoord, gpsError);
	    		
	    		LocationUpdate.natveInsert(LocationUpdate.em(), imei, observation, charging, battery, dateTime, timeSentDate, timeReceivedDate, boot, shutdown, failednetwork, signal);
    		}
    		catch(Exception e)
    		{
    			Logger.error("Bad location update string: ", line);
    			
    			// couldn't parse results
    			badRequest();
    		}
    	}	
    	
    	
    	if(observation != null) {
	    	Phone phone = Phone.find("imei = ?", observation.getVehicleId()).first();
			
			if(phone != null)
			{
				phone.recentLat = observation.getObsCoordsLatLon().y;
				phone.recentLon = observation.getObsCoordsLatLon().x;
				phone.lastUpdate = new Date();
				
				phone.save();
			}
    	}
    	
        ok();
    }
    
    
	static public void traces()
	{
		List<LocationUpdate> updates = LocationUpdate.find("order by timestamp desc").fetch(100);
		
		renderJSON(updates);
	}
    
    static public void network()
    {
    	List<LocationUpdate> updates = LocationUpdate.find("failedNetwork = true").fetch();
    	
    	Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    	
    	renderJSON(gson.toJson(updates));
    }
   
    public static void path(String graphId, String lat1, String lon1, String lat2, String lon2)
		   throws JsonGenerationException, JsonMappingException, IOException {
	    final Coordinate coord1 =
	        new Coordinate(Double.parseDouble(lon1), Double.parseDouble(lat1));
	    final Coordinate coord2 =
	        new Coordinate(Double.parseDouble(lon2), Double.parseDouble(lat2));
	   
	    Path path = new Path();
	    
	    List<Integer> edgeIds = Api.getGraph(graphId).getEdgesBetweenPoints(coord1, coord2);
	
		path.distance = 0.0;
		
	    for(Integer edgeId : edgeIds)
	    {  		
	    	TrafficEdge edge = Api.getGraph(graphId).getTrafficEdge(edgeId);
	    	Geometry geom = edge.getGeometry();
	    	
	    	org.opentripplanner.util.model.EncodedPolylineBean polylineBean =  PolylineEncoder.createEncodings(geom);
	    	
	    	path.addEdge(edgeId, edge.geLength(), polylineBean.getPoints());
	    	
	    	path.distance += edge.geLength();
	    }
	    
	    renderJSON(path);
	} 
    
    public static void edges(String graphId, String edgeIds) {
 	    
    	ArrayList<Integer> edges = new ArrayList<Integer>();
		
		if(edgeIds != null) {
			String[] ids = edgeIds.split(",");
			
			if(ids.length > 0) {
			
				for(String id : ids) {
					edges.add(Integer.parseInt(id));
				}
			}
		}
    	
    	Path path = new Path();
 	    
 	 
 		path.distance = 0.0;
 		
 	    for(Integer edgeId : edges)
 	    {  		
 	    	TrafficEdge edge = Api.getGraph(graphId).getTrafficEdge(edgeId);
 	    	
 	    	path.addEdge(edgeId, edge.geLength(), edge.getEncodedGeometry());
 	    	
 	    	path.distance += edge.geLength();
 	    }
 	    
 	    renderJSON(path);
 	} 
    
    public static void trafficStats(String graphId, String edgeIds, String daysOfWeek, Long fromDate, Long toDate, Integer minHour, Integer maxHour) {
		
		Http.Header hd = new Http.Header();
		
		hd.name = "Access-Control-Allow-Origin";
		hd.values = new ArrayList<String>();
		hd.values.add("*");
		
		HashSet<Long> filteredEdges = null;
		
		if(edgeIds != null) {
			String[] ids = edgeIds.split(",");
			
			if(ids.length > 0) {
				filteredEdges = new HashSet<Long>();
				for(String id : ids) {
					filteredEdges.add(Long.parseLong(id));
				}
			}
		}
		
		HashSet<Integer> days = null;
		
		if(daysOfWeek != null) {
			String[] dayArray = daysOfWeek.split(",");
			
			if(dayArray.length > 0) {
				days = new HashSet<Integer>();
				for(String day : dayArray) {
					days.add(Integer.parseInt(day));
				}
			}
		}
		
		Date from = null;
		
		if(fromDate != null) 
			from = new Date(fromDate);
		
		Date to = null;
		
		if(toDate != null)
			to = new Date(toDate);
			
		TrafficStats stats = new TrafficStats(graphId, from, to, days, minHour, maxHour, filteredEdges);
		
		TrafficStatsResponse response = stats.getEdgeSpeeds(null);
	
		renderJSON(response);
	}
    
    public static void currentConditions(String graphId, Integer hours) {
		
		Http.Header hd = new Http.Header();
		
		hd.name = "Access-Control-Allow-Origin";
		hd.values = new ArrayList<String>();
		hd.values.add("*");
		
		Calendar cal = Calendar.getInstance(); 
		
		cal.setTime(new Date()); 
	
		TrafficStats stats = new TrafficStats(graphId, cal.getTime(), null);
		
		if(hours != null && hours > 1) {
			while(hours > 1) {
				
				cal.add(Calendar.HOUR_OF_DAY, -1);
				
				TrafficStats s = new TrafficStats(graphId, cal.getTime(), null);
				
				stats.add(s);
				
				hours--;
			}
		}
		
		TrafficStatsResponse response = stats.getEdgeSpeeds(null);
	
		renderJSON(response);
	}
    
    public static void baselineConditions(String graphId, Integer hours) {
		
		Http.Header hd = new Http.Header();
		
		hd.name = "Access-Control-Allow-Origin";
		hd.values = new ArrayList<String>();
		hd.values.add("*");
		
		Calendar cal = Calendar.getInstance(); 
		
		TrafficStats stats = new TrafficStats();
		
		for(int i = 1; i <= 4; i++) {
		
			cal.setTime(new Date()); 
			cal.add(Calendar.WEEK_OF_YEAR, 0 - i);
			
			Integer h = hours;
			
			if(h != null && h > 1) {
				
				while(h > 1) {
					
					cal.add(Calendar.HOUR_OF_DAY, -1);
					
					TrafficStats s = new TrafficStats(graphId, cal.getTime(), null);
					
					stats.add(s);
					
					h--;
				}
			}
			else {
				TrafficStats s = new TrafficStats(graphId, cal.getTime(), null);
				stats.add(s);
			}
		}
		
		
		
		TrafficStatsResponse response = stats.getEdgeSpeeds(null);
	
		renderJSON(response);
	}
    
}
