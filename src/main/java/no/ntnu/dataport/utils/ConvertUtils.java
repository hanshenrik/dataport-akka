package no.ntnu.dataport.utils;

import com.fatboyindustrial.gsonjodatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import no.ntnu.dataport.types.Messages;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ConvertUtils {
    private static Gson gson = Converters.registerDateTime(new GsonBuilder()).create();


    public static float hex8BytesToFloat(String hex) {
        Long value = Long.parseLong(hex, 16);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.asLongBuffer().put(value);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.asFloatBuffer().get();
    }

    public static int hex2BytesToInt(String hex) {
        return Integer.parseInt(hex, 16);
    }

    public static Messages.Observation convertToObservation(MqttMessage message) {
        Messages.CTT2Observation CTT2Observation = gson.fromJson(new String(message.getPayload()), Messages.CTT2Observation.class);

        String payloadBase64 = CTT2Observation.payload;
        byte[] payloadBytes = Base64.decodeBase64(payloadBase64);
        String payloadHexWithHeader = Hex.encodeHexString(payloadBytes);
        String payloadHexOnlyData = payloadHexWithHeader.substring(36); // Skip the header
        float co2 = hex8BytesToFloat(payloadHexOnlyData.substring(2, 10));
        float no2 = hex8BytesToFloat(payloadHexOnlyData.substring(12, 20));
        float temp = hex8BytesToFloat(payloadHexOnlyData.substring(22, 30));
        float hum = hex8BytesToFloat(payloadHexOnlyData.substring(32, 40));
        float pres = hex8BytesToFloat(payloadHexOnlyData.substring(42, 50));
        int bat;

        Messages.Data data;
        if (payloadHexOnlyData.length() > 55) {
            float pm1 = hex8BytesToFloat(payloadHexOnlyData.substring(52, 60));
            float pm2 = hex8BytesToFloat(payloadHexOnlyData.substring(62, 70));
            float pm10 = hex8BytesToFloat(payloadHexOnlyData.substring(72, 80));
            bat = hex2BytesToInt(payloadHexOnlyData.substring(82, 84));
            data = new Messages.Data(co2, no2, temp, hum, pres, pm1, pm2, pm10, bat);
        }
        else {
            bat = hex2BytesToInt(payloadHexOnlyData.substring(52, 54));
            data = new Messages.Data(co2, no2, temp, hum, pres, bat);
        }
        return new Messages.Observation(CTT2Observation.dev_eui, CTT2Observation.metadata.get(0), data);
    }
}
