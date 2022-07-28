package ai.vito.openapi.batch;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.vito.openapi.auth.Auth;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TranscribeClient {
	public static final String TRANSCRIBE_ENDPOINT = "https://openapi.vito.ai/v1/transcribe";
	
	public static void main(String[] args) throws Exception {
		String token = Auth.getAccessToken();
		String transcribe_id = PostTranscribe(token);
		
		for(int i=0;i<5;i++) {
			Thread.sleep(1000);
			Map<String,Object> map = GetTranscribe(token , transcribe_id);
			if (map.get("status").equals("completed")) {
				System.out.println(map.get("results"));
				break;
			}else if (map.get("status").equals("failed")) {
				System.out.println("transcribe error!!");
				break;
			}else {
				System.out.println(map.get("status"));
			}
		}
	}
	
	public static String PostTranscribe(String token) throws Exception {
		OkHttpClient client = new OkHttpClient();
		
		File file = new File("sample.wav");
		
		Map<String, Object> map = new HashMap<String, Object>();        
		map.put("use_multi_channel", false);
		map.put("use_itn", true);
		map.put("use_disfluency_filter",true);
		map.put("paragraph_splitter", new HashMap<String, Integer>() {
	        {
	            put("min", 10);
	            put("max", 50);
	        }
	    });
		map.put("diarization", new HashMap<String, Boolean>() {
	        {
	            put("use_ars", false);
	            put("use_verification", false);
	        }
	    });
		
		ObjectMapper objectMapper = new ObjectMapper();         
		String configJson = objectMapper.writeValueAsString(map);
          
		RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("file", file.getName(),RequestBody.create(file, null)) 
				.addFormDataPart("config", configJson)
				.build();
		
	    Request request = new Request.Builder()
	        .url(TRANSCRIBE_ENDPOINT)
	        .addHeader("Authorization","Bearer "+token)
	        .post(requestBody)
	        .build();

	    Response response = client.newCall(request).execute();
	    HashMap<String,String> resultMap = objectMapper.readValue(response.body().string(), HashMap.class);
	    
	    System.out.println(resultMap.get("id"));
		return resultMap.get("id");
	}
	
	public static HashMap<String,Object> GetTranscribe(String token ,String transcribe_id) throws Exception {
		OkHttpClient client = new OkHttpClient();
		
	    Request request = new Request.Builder()
	        .url(TRANSCRIBE_ENDPOINT+"/"+transcribe_id)
	        .addHeader("Authorization","Bearer "+token)
	        .get()
	        .build();

	    Response response = client.newCall(request).execute();
	    
	    ObjectMapper objectMapper = new ObjectMapper();
	    HashMap<String,Object> resultMap = objectMapper.readValue(response.body().string(), HashMap.class);
		return resultMap;
	}
}
