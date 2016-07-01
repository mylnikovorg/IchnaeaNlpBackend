/*
 * Copyright 2016 Mylnikov Alexander
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mylnikov.api.geolocation.unifiednlp.backend;

import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Process;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.microg.nlp.api.CellBackendHelper;
import org.microg.nlp.api.HelperLocationBackendService;
import org.microg.nlp.api.LocationHelper;
import org.microg.nlp.api.WiFiBackendHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;

import static org.microg.nlp.api.CellBackendHelper.Cell;
import static org.microg.nlp.api.WiFiBackendHelper.WiFi;

public class BackendService extends HelperLocationBackendService
        implements WiFiBackendHelper.Listener, CellBackendHelper.Listener {

    private static final String TAG = "MylnikovBackendService";
    private static final String SERVICE_URL_CELL = "https://api.mylnikov.org/geolocation/cell?v=1.1";
    private static final String SERVICE_URL_WIFI = "https://api.mylnikov.org/geolocation/wifi?v=1.1";

    private static final String SEARCH_STRING_REQUEST = "&search=";

    private static final String PROVIDER = "mylnikov-geo";
    private static final int RATE_LIMIT_MS = 5000;

    private static BackendService instance;

    private boolean running = false;
    private Set<WiFi> wiFis;
    private Set<Cell> cells;
    private Thread thread;
    private long lastRequest = 0;

    private boolean useWiFis = true;
    private boolean useCells = true;

    @Override
    public synchronized void onCreate() {
        super.onCreate();
        reloadSettings();
        reloadInstanceSettings();
    }

    @Override
    protected synchronized void onOpen() {
        super.onOpen();
        reloadSettings();
        instance = this;
        running = true;
        Log.d(TAG, "Activating instance at process " + Process.myPid());
    }

    public static void reloadInstanceSettings() {
        if (instance != null) {
            instance.reloadSettings();
        } else {
            Log.d(TAG, "No instance found active.");
        }
    }

    private void reloadSettings() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        removeHelpers();
        if (preferences.getBoolean("use_cells", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            addHelper(new CellBackendHelper(this, this));
        } else {
            cells = null;
        }
        if (preferences.getBoolean("use_wifis", true)) {
            addHelper(new WiFiBackendHelper(this, this));
        } else {
            wiFis = null;
        }
    }

    @Override
    protected synchronized void onClose() {
        super.onClose();
        running = false;
        if (instance == this) {
            instance = null;
            Log.d(TAG, "Deactivating instance at process " + Process.myPid());
        }
    }

    @Override
    public void onWiFisChanged(Set<WiFi> wiFis) {
        this.wiFis = wiFis;
        if (running) startCalculate();
    }

    @Override
    public void onCellsChanged(Set<Cell> cells) {
        this.cells = cells;
        Log.d(TAG, "Cells: " + cells.size());
        if (running) startCalculate();
    }

    private synchronized void startCalculate() {
        if (thread != null) return;
        if (lastRequest + RATE_LIMIT_MS > System.currentTimeMillis()) return;
        final Set<WiFi> wiFis = this.wiFis;
        final Set<Cell> cells = this.cells;
        if ((cells == null || cells.isEmpty()) && (wiFis == null || wiFis.size() < 2)) return;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String requestWifiPath = getRequestWifiPath(wiFis);
                    if (requestWifiPath != null) {
                        conn = (HttpURLConnection) new URL(requestWifiPath).openConnection();
                        conn.setDoOutput(true);
                        conn.setDoInput(true);
                        String r = new String(readStreamToEnd(conn.getInputStream()));
                        Log.d(TAG, "response: " + r);
                        JSONObject response = new JSONObject(r);
                        if (response.has("result") && response.getInt("result") == 200 && response.has("data")) {
                            JSONObject data = response.getJSONObject("data");
                            double lat = data.getDouble("lat");
                            double lon = data.getDouble("lon");
                            double acc = data.getDouble("accuracy");
                            report(LocationHelper.create(PROVIDER, lat, lon, (float) acc));
                            return;
                        }
                    }
                    String requestCellPath = getRequestCellPath(cells);
                    if (requestCellPath != null) {
                        conn = (HttpURLConnection) new URL(requestCellPath).openConnection();
                        conn.setDoOutput(true);
                        conn.setDoInput(true);
                        String r = new String(readStreamToEnd(conn.getInputStream()));
                        Log.d(TAG, "response: " + r);
                        JSONObject response = new JSONObject(r);
                        if (response.has("result") && response.getInt("result") == 200 && response.has("data")) {
                            JSONObject data = response.getJSONObject("data");
                            double lat = data.getDouble("lat");
                            double lon = data.getDouble("lon");
                            double acc = data.getDouble("accuracy");
                            report(LocationHelper.create(PROVIDER, lat, lon, (float) acc));
                            return;
                        }
                    }
                } catch (IOException | JSONException e) {
                    if (conn != null) {
                        InputStream is = conn.getErrorStream();
                        if (is != null) {
                            try {
                                String error = new String(readStreamToEnd(is));
                                Log.w(TAG, "Error: " + error);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    Log.w(TAG, e);
                }

                lastRequest = System.currentTimeMillis();
                thread = null;
            }
        });
        thread.start();
    }

    @Override
    public void report(Location location) {
        Log.d(TAG, "reporting: " + location);
        super.report(location);
    }

    private static byte[] readStreamToEnd(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buff = new byte[1024];
            while (true) {
                int nb = is.read(buff);
                if (nb < 0) {
                    break;
                }
                bos.write(buff, 0, nb);
            }
            is.close();
        }
        return bos.toByteArray();
    }

    /**
     * see https://mozilla-ichnaea.readthedocs.org/en/latest/cell.html
     */
    @SuppressWarnings("MagicNumber")
    private static int calculateAsu(Cell cell) {
        switch (cell.getType()) {
            case GSM:
                return Math.max(0, Math.min(31, (cell.getSignal() + 113) / 2));
            case UMTS:
                return Math.max(-5, Math.max(91, cell.getSignal() + 116));
            case LTE:
                return Math.max(0, Math.min(95, cell.getSignal() + 140));
            case CDMA:
                int signal = cell.getSignal();
                if (signal >= -75) {
                    return 16;
                }
                if (signal >= -82) {
                    return 8;
                }
                if (signal >= -90) {
                    return 4;
                }
                if (signal >= -95) {
                    return 2;
                }
                if (signal >= -100) {
                    return 1;
                }
                return 0;
        }
        return 0;
    }

    private static String getRadioType(Cell cell) {
        switch (cell.getType()) {
            case CDMA:
                return "cdma";
            case LTE:
                return "lte";
            case UMTS:
                return "wcdma";
            case GSM:
            default:
                return "gsm";
        }
    }

    private static String getRequestCellPath(Set<Cell> cells) throws JSONException, UnsupportedEncodingException {
        StringBuilder stringBufferRequest = new StringBuilder();
        if (cells != null && cells.size() != 0) {
            for (Cell cell : cells) {
                stringBufferRequest.append(cell.getMcc()).append(",");
                stringBufferRequest.append(cell.getMnc()).append(",");
                stringBufferRequest.append(cell.getLac()).append(",");
                stringBufferRequest.append(cell.getCid()).append(",");
                stringBufferRequest.append(cell.getSignal()).append(";");
            }
            return SERVICE_URL_CELL + SEARCH_STRING_REQUEST + Base64.encodeToString(stringBufferRequest.toString().getBytes("UTF-8"), Base64.NO_WRAP);
        }
        return null;
    }

    private static String getRequestWifiPath(Set<WiFi> wiFis) throws JSONException, UnsupportedEncodingException {
        StringBuilder stringBufferRequest = new StringBuilder();
        if (wiFis != null && wiFis.size() != 0) {
            for (WiFi wiFi : wiFis) {
                stringBufferRequest.append(wiFi.getBssid()).append(",");
                stringBufferRequest.append(wiFi.getRssi()).append(";");
            }
            return SERVICE_URL_WIFI + SEARCH_STRING_REQUEST + Base64.encodeToString(stringBufferRequest.toString().getBytes("UTF-8"), Base64.NO_WRAP);
        }
        return null;
    }

}
