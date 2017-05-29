package net.st4ndard.camera.activity;

import android.app.Activity;
import android.databinding.DataBindingUtil;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;

import net.st4ndard.camera.R;
import net.st4ndard.camera.databinding.ActivityMainBinding;
import net.st4ndard.camera.util.AutoFitImageView;
import net.st4ndard.camera.util.Camera2StateMachine;

import java.util.List;


public class MainActivity extends Activity implements SensorEventListener {
    private SensorManager manager;
    private Camera2StateMachine mCamera2;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        mCamera2 = new Camera2StateMachine();
        manager = (SensorManager) getSystemService(SENSOR_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCamera2.open(this, binding.imageView);
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        if (sensors.size() > 0) {
            Sensor s = sensors.get(0);
            manager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        mCamera2.close();
        super.onPause();
    }

    @Override
    protected void onStop() {
        manager.unregisterListener(this);
        super.onStop();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }


    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            binding.textViewx.setText("X = " + String.valueOf(event.values[SensorManager.DATA_X]));
            binding.textViewy.setText("Y = " +String.valueOf(event.values[SensorManager.DATA_Y]));
            binding.textViewz.setText("Z = " +String.valueOf(event.values[SensorManager.DATA_Z]));
            mCamera2.setAxis((int)event.values[SensorManager.DATA_Z]);
        }
    }
}