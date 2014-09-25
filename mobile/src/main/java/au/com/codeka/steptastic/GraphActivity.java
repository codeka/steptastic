package au.com.codeka.steptastic;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.NavUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;

import au.com.codeka.steptastic.graph.Bar;
import au.com.codeka.steptastic.graph.BarGraph;
import au.com.codeka.steptastic.graph.HoloGraphAnimate;
import au.com.codeka.steptastic.graph.Line;
import au.com.codeka.steptastic.graph.LineGraph;
import au.com.codeka.steptastic.graph.LinePoint;


public class GraphActivity extends Activity {
    Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        showDayOfWeekHistogram();
        showFifteenMinuteIntervalHistogram();
    }

    private void showDayOfWeekHistogram() {
        ArrayList<Bar> bars = new ArrayList<Bar>();
        final BarGraph barGraph = (BarGraph) findViewById(R.id.day_of_week_histogram);
        StepDataStore.AverageStepCountPerUnitOfTime[] histogram = StepDataStore.i.getDayOfWeekHistogram();
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        for (int i = 0; i < 7; i++) {
            Bar bar = new Bar();
            bar.setColor(0xff99cc00);
            bar.setName(days[i]);
            bar.setValue(0);
            bar.setGoalValue((float) histogram[i].totalSteps / histogram[i].count);
            bars.add(bar);
        }
        barGraph.setShowBarText(false);
        barGraph.setShowPopup(false);
        barGraph.setBars(bars);
        barGraph.setDuration(1500);
        barGraph.setInterpolator(new DecelerateInterpolator());

        handler.post(new Runnable() {
            @Override
            public void run() {
                barGraph.animateToGoalValues();
            }
        });
    }

    private void showFifteenMinuteIntervalHistogram() {
        final LineGraph lineGraph = (LineGraph) findViewById(R.id.fifteen_minute_interval_histogram);

        StepDataStore.AverageStepCountPerUnitOfTime[] histogram = StepDataStore.i.getFifteenMinuteIntervalHistogram();
        Line line = new Line();
        float maxValue = 0.0f;
        for (int i = 0; i < 96; i++) {
            LinePoint point = new LinePoint();
            point.setX(i);
            float value = (float) histogram[i].totalSteps / histogram[i].count;
            if (value > maxValue) {
                maxValue = value;
            }
            point.setY(value);
            line.addPoint(point);
        }
        line.setColor(Color.BLUE);
        line.setStrokeWidth(4);
        line.setShowingPoints(false);
        lineGraph.addLine(line);
        lineGraph.resetXLimits();
        lineGraph.resetYLimits();
        lineGraph.setRangeY(-1.0f, maxValue);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // Respond to the action bar's Up/Home button
            case android.R.id.home:
                NavUtils.navigateUpFromSameTask(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
