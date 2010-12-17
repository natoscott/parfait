package com.custardsource.parfait;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.measure.unit.Unit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import org.apache.log4j.Logger;

/**
 * Monitors the value returned by calls at the provided interval to the provided
 * {@link Poller}.
 */
public class PollingMonitoredValue<T> extends SettableValue<T> {
	private static final Logger LOG = Logger.getLogger("parfait.polling");

	/**
	 * The minimum time in ms that may be specified as an updateInterval.
	 */
	private static final int MIN_UPDATE_INTERVAL = 250;

	private static final Timer POLLING_TIMER = new Timer(
			"PollingMonitoredValue-poller", true);

	/**
	 * All timer tasks that have been scheduled in PollingMonitoredValues;
	 * useful only for testing.
	 */
	private static final List<TimerTask> SCHEDULED_TASKS = new CopyOnWriteArrayList<TimerTask>();

	private final Poller<T> poller;

	/**
	 * Creates a new {@link PollingMonitoredValue} with the specified polling
	 * interval.
	 * 
	 * @param updateInterval
	 *            how frequently the Poller should be checked for updates (may
	 *            not be less than {@link #MIN_UPDATE_INTERVAL}
	 */
	public PollingMonitoredValue(String name, String description,
			MonitorableRegistry registry, int updateInterval, Poller<T> poller,
			ValueSemantics semantics) {
		this(name, description, registry, updateInterval, poller, semantics,
				Unit.ONE);
	}

	/**
	 * Creates a new {@link PollingMonitoredValue} with the specified polling
	 * interval.
	 * 
	 * @param updateInterval
	 *            how frequently the Poller should be checked for updates (may
	 *            not be less than {@link #MIN_UPDATE_INTERVAL}
	 */
	public PollingMonitoredValue(String name, String description,
			MonitorableRegistry registry, int updateInterval, Poller<T> poller,
			ValueSemantics semantics, Unit<?> unit) {
		super(name, description, registry, poller.poll(), unit, semantics);
		this.poller = poller;
		Preconditions.checkState(updateInterval >= MIN_UPDATE_INTERVAL,
				"updateInterval is too short.");
		TimerTask task = new PollerTask();
		SCHEDULED_TASKS.add(task);
		POLLING_TIMER.scheduleAtFixedRate(task, updateInterval, updateInterval);
	}

	@Override
	public String toString() {
		return Objects.toStringHelper(this).add("name", getName())
				.add("description", getDescription()).add("poller", poller)
				.toString();
	}

	private class PollerTask extends TimerTask {
		@Override
		public void run() {
			try {
				set(poller.poll());
			} catch (Throwable t) {
				LOG.error("Error running poller " + this
						+ "; will rerun next cycle", t);
			}
		}
	}

	@VisibleForTesting
	static void runAllTasks() {
		for (TimerTask task : SCHEDULED_TASKS) {
			task.run();
		}
	}

	/**
	 * Convenient factory method to create pollers you don't care about keeping
	 * – that is, pollers which should be registered and start updating their
	 * value, but which you don't need to hold a reference to (because you will
	 * presumably just be modifying the polled source).
	 */
	public static <T> void poll(String name, String description,
			MonitorableRegistry registry, int updateInterval, Poller<T> poller,
			ValueSemantics semantics, Unit<?> unit) {
		new PollingMonitoredValue<T>(name, description, registry,
				updateInterval, poller, semantics, unit);
	}
}