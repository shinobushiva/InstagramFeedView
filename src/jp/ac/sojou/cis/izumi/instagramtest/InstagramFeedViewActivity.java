package jp.ac.sojou.cis.izumi.instagramtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.ImageView;

public class InstagramFeedViewActivity extends Activity {

	/**
	 * 認証用URL
	 */
	private static final String OAUTH_AUTHORIZE_URL = "https://api.instagram.com/oauth/authorize/";

	/**
	 * フィード取得用URL
	 */
	private static final String USERS_SELF_FEED_URL = "https://api.instagram.com/v1/users/self/feed";

	/**
	 * コールバックURL - AndroidManifest.xmlでの設定が必要
	 */
	private static final String OAUTH_CALLBACK_URL = "instagram://callback";

	/**
	 * Instagramに登録したアプリのクライアントID
	 */
	private static final String CLIENT_ID = "227b92a281464186be493c677d4f4f41";

	/**
	 * 一度に取得するフィードの数
	 */
	private static final int NUM_FEEDS = 20;
	/**
	 * 画像の更新を行う最小時間
	 */
	private static final int VIEW_UPDATE_DURATION = 10 * 1000;
	/**
	 * Instagramからフィードを取得する間隔
	 */
	private static final int INATAGRAM_UPDATE_DURATION = 5 * 60 * 1000;

	/**
	 * 画像を表示するビュー
	 */
	private ImageView mImageView;
	/**
	 * アクセストークン
	 */
	private String token;

	/**
	 * Instagramから取得した画像URLのリスト
	 */
	private List<String> imageUrls;
	/**
	 * 現在表示している画像URLの番号
	 */
	private int imageUrlPointer = 0;
	/**
	 * 画像の更新中を示すフラグ
	 */
	private boolean imageUpdating = false;

	/**
	 * 処理状態を保持
	 */
	private static State state = State.DEFAULT;

	/**
	 * 処理状態を表す列挙(enum)
	 */
	private enum State {
		DEFAULT, // 初期状態
		GET_ACCESS_TOKEN, // アクセストークンの取得要求
		ACCESS_TOKEN_REQUESTED, // アクセストークンの取得要求応答
		HAS_ACCESS_TOKEN // アクセストークンを保持している
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		Log.d("TEST", "OnCreate");

		mImageView = (ImageView) findViewById(R.id.imageView);

	}

	@Override
	protected void onResume() {
		super.onResume();

		// アクセストークンをアプリケーション設定から取得
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		token = prefs.getString("access_token", null);
		Log.d("TEST", "" + token);
		Log.d("TEST", state.name());

		// 保存されているアクセストークンをチェック
		if (state != State.ACCESS_TOKEN_REQUESTED) {
			if (token == null) {
				state = State.GET_ACCESS_TOKEN;
			} else {
				state = State.HAS_ACCESS_TOKEN;
			}
		}

		// アクセストークンの取得要求
		if (state == State.GET_ACCESS_TOKEN) {
			String url = OAUTH_AUTHORIZE_URL + "?client_id=" + CLIENT_ID
					+ "&redirect_uri=" + URLEncoder.encode(OAUTH_CALLBACK_URL)
					+ "&response_type=token";
			Log.d("TEST", url);

			state = State.ACCESS_TOKEN_REQUESTED;
			// ブラウザを起動
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
		}

		// アクセストークンの取得要求に対する応答
		if (state == State.ACCESS_TOKEN_REQUESTED) {
			Uri uri = getIntent().getData();
			if (uri != null) {
				String uripath = uri.toString();
				Log.d("TEST", uripath);

				token = uripath.split("#")[1];
				Log.d("TEST", token);
				prefs.edit().putString("access_token", token).commit();
				state = State.HAS_ACCESS_TOKEN;
			}
		}

		if (state == State.HAS_ACCESS_TOKEN) {
			// Instagramから一定時間ごとに画像を取得してくるタイマータスク
			TimerTask instagramReadTimerTask = new TimerTask() {

				@Override
				public void run() {
					getImages(token);
				}
			};
			Timer instagramReadTimer = new Timer();
			instagramReadTimer.scheduleAtFixedRate(instagramReadTimerTask, 0,
					INATAGRAM_UPDATE_DURATION);

			// GUI更新のためのハンドラ
			final Handler handler = new Handler();

			// 画像を更新するタイマータスク
			TimerTask imageUpdateTimerTask = new TimerTask() {
				@Override
				public void run() {
					if (imageUpdating)
						return;

					imageUpdating = true;
					Log.d("TEST", "imageUpdating");
					final Bitmap bm = nextImage();
					Log.d("TEST", "" + bm);
					if (bm != null) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								Log.d("TEST", "setImageBitmap");
								mImageView.setImageBitmap(bm);
								mImageView.invalidate();
							}
						});
					}
					imageUpdating = false;
				}
			};
			Timer imageUpdateTimer = new Timer();
			imageUpdateTimer.scheduleAtFixedRate(imageUpdateTimerTask, 0,
					VIEW_UPDATE_DURATION);
		}
	}

	/**
	 * 次に表示する画像を取得
	 * 
	 * @return
	 */
	private Bitmap nextImage() {

		if (imageUrls == null) {
			return null;
		}

		try {
			String u = imageUrls.get(imageUrlPointer);
			Bitmap bmp = BitmapFactory
					.decodeStream(connect(u).getInputStream());
			imageUrlPointer = (imageUrlPointer + 1) % imageUrls.size();

			return bmp;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Instagramから画像のURLを取得
	 * 
	 * @param token
	 */
	private void getImages(String token) {
		try {
			// 最新のフィードn個を取得
			URL url = new URL(USERS_SELF_FEED_URL + "?" + token + "&count="
					+ NUM_FEEDS);
			Log.d("TEST", "" + url);
			HttpURLConnection connect = connect(url);
			BufferedReader br = new BufferedReader(new InputStreamReader(
					connect.getInputStream()));
			StringBuilder sb = new StringBuilder();
			String line = null;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}

			List<String> ius = new ArrayList<String>();

			// JSONとして処理
			JSONObject jo = new JSONObject(sb.toString());
			Log.d("TEST", "" + jo.toString());
			JSONArray ja = jo.getJSONArray("data");
			for (int i = 0; i < ja.length(); i++) {
				// フィードから画像URLを取り出しリストに格納
				String imageUrl = ja.getJSONObject(i).getJSONObject("images")
						.getJSONObject("standard_resolution").getString("url");
				Log.d("TEST", "" + imageUrl);
				ius.add(imageUrl);
			}
			imageUrlPointer = 0;
			imageUrls = ius;

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 証明書を検証せずにすべてのサーバーを信頼します
	 */
	private final static HostnameVerifier DO_NOT_VERIFY = new HostnameVerifier() {
		public boolean verify(String hostname, SSLSession session) {
			return true;
		}
	};

	/**
	 * 証明書を検証せずにすべてのサーバーを信頼します
	 */
	private static void trustAllHosts() {
		// Create a trust manager that does not validate certificate chains
		TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
			public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				return new java.security.cert.X509Certificate[] {};
			}

			public void checkClientTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {
			}

			public void checkServerTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {
			}
		} };

		// Install the all-trusting trust manager
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			HttpsURLConnection
					.setDefaultSSLSocketFactory(sc.getSocketFactory());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * URLからHttp接続を取得します
	 */
	private HttpURLConnection connect(URL url) throws IOException {
		HttpURLConnection http = null;

		if (url.getProtocol().toLowerCase().equals("https")) {
			trustAllHosts();
			HttpsURLConnection https = (HttpsURLConnection) url
					.openConnection();
			https.setHostnameVerifier(DO_NOT_VERIFY);
			http = https;
		} else {
			http = (HttpURLConnection) url.openConnection();
		}

		return http;
	}

	/**
	 * URL文字列からHttp接続を取得します
	 */
	private HttpURLConnection connect(String u) {
		try {
			return connect(new URL(u));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
}