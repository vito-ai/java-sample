package ai.vito.openapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;

public class Auth {
	public static String CLIENT_ID = "<YOUR_CLIENT_ID>";
	public static String CLIENT_SECRET = "<YOUR_CLIENT_SECRET>";
	
    public static String getAccessToken() throws IOException {
        OkHttpClient client = new OkHttpClient();
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build();

        Request request = new Request.Builder()
                .url("https://openapi.vito.ai/v1/authenticate")
                .post(formBody)
                .build();

        Response response = client.newCall(request).execute();
        ObjectMapper objectMapper = new ObjectMapper();
        HashMap<String, String> map = objectMapper.readValue(response.body().string(), HashMap.class);

        return map.get("access_token");
    }
}
