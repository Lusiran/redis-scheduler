package com.github.davidmarquis.redisscheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

public class RedisTaskScheduler implements TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskScheduler.class);

    private static final String SCHEDULE_KEY = "redis-scheduler.%s";
    private static final String DEFAULT_SCHEDULER_NAME = "scheduler";

    private Clock clock = Clock.systemDefaultZone();
    private RedisDriver driver;
    private TaskTriggerListener taskTriggerListener;

    /**
     * Delay between each polling of the scheduled tasks. The lower the value, the best precision in triggering tasks.
     * However, the lower the value, the higher the load on Redis.
     */
    private int pollingDelayMillis = 10000;

    /**
     * If you need multiple schedulers for the same application, customize their names to differentiate in logs.
     */
    private String schedulerName = DEFAULT_SCHEDULER_NAME;

    private PollingThread pollingThread;
    private int maxRetriesOnConnectionFailure = 1;

    public RedisTaskScheduler(RedisDriver driver, TaskTriggerListener taskTriggerListener) {
        this.driver = driver;
        this.taskTriggerListener = taskTriggerListener;
    }

    @SuppressWarnings("unchecked")
    public void runNow(String taskId) {
        scheduleAt(taskId, clock.instant());
    }

    @SuppressWarnings("unchecked")
    public void scheduleAt(String taskId, Instant triggerTime) {
        if (triggerTime == null) {
            throw new IllegalArgumentException("A trigger time must be provided.");
        }

        driver.execute(commands -> commands.addToSetWithScore(keyForScheduler(), taskId, triggerTime.toEpochMilli()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void unschedule(String taskId) {
        driver.execute(commands -> commands.removeFromSet(keyForScheduler(), taskId));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void unscheduleAllTasks() {
        driver.execute(commands -> commands.remove(keyForScheduler()));
    }

    @PostConstruct
    public void initialize() {
        pollingThread = new PollingThread();
        pollingThread.setName(schedulerName + "-polling");

        pollingThread.start();

        log.info(String.format("[%s] Started Redis Scheduler (polling freq: [%sms])", schedulerName, pollingDelayMillis));
    }

    @PreDestroy
    public void destroy() {
        if (pollingThread != null) {
            pollingThread.requestStop();
        }
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setSchedulerName(String schedulerName) {
        this.schedulerName = schedulerName;
    }

    public void setPollingDelayMillis(int pollingDelayMillis) {
        this.pollingDelayMillis = pollingDelayMillis;
    }

    public void setMaxRetriesOnConnectionFailure(int maxRetriesOnConnectionFailure) {
        this.maxRetriesOnConnectionFailure = maxRetriesOnConnectionFailure;
    }

    private String keyForScheduler() {
        return String.format(SCHEDULE_KEY, schedulerName);
    }

    @SuppressWarnings("unchecked")
    private boolean triggerNextTaskIfFound() {

        return driver.fetch(commands -> {
            boolean taskWasTriggered = false;
            final String key = keyForScheduler();

            commands.watch(key);

            Optional<String> nextTask = commands.firstByScore(keyForScheduler(), 0, clock.millis());

            if (nextTask.isPresent()) {
                String nextTaskId = nextTask.get();

                commands.multi();
                commands.removeFromSet(key, nextTaskId);
                boolean executionSuccess = commands.exec();

                if (executionSuccess) {
                    log.debug(String.format("[%s] Triggering execution of task [%s]", schedulerName, nextTaskId));

                    tryTaskExecution(nextTaskId);
                    taskWasTriggered = true;
                } else {
                    log.warn(String.format("[%s] Race condition detected for triggering of task [%s]. " +
                                                   "The task has probably been triggered by another instance of this application.", schedulerName, nextTaskId));
                }
            } else {
                commands.unwatch();
            }

            return taskWasTriggered;
        });
    }

    private void tryTaskExecution(String task) {
        try {
            taskTriggerListener.taskTriggered(task);
        } catch (Exception e) {
            log.error(String.format("[%s] Error during execution of task [%s]", schedulerName, task), e);
        }
    }

    private class PollingThread extends Thread {
        private boolean stopRequested = false;
        private int numRetriesAttempted = 0;

        public void requestStop() {
            stopRequested = true;
        }

        @Override
        public void run() {
            try {
                while (!stopRequested && !isMaxRetriesAttemptsReached()) {

                    try {
                        attemptTriggerNextTask();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.error(String.format(
                        "[%s] Error while polling scheduled tasks. " +
                                "No additional scheduled task will be triggered until the application is restarted.", schedulerName), e);
            }

            if (isMaxRetriesAttemptsReached()) {
                log.error(String.format("[%s] Maximum number of retries (%s) after Redis connection failure has been reached. " +
                                                "No additional scheduled task will be triggered until the application is restarted.", schedulerName, maxRetriesOnConnectionFailure));
            } else {
                log.info("[%s] Redis Scheduler stopped");
            }
        }

        private void attemptTriggerNextTask() throws InterruptedException {
            try {
                boolean taskTriggered = triggerNextTaskIfFound();

                // if a task was triggered, we'll try again immediately. This will help to speed up the execution
                // process if a few tasks were due for execution.
                if (!taskTriggered) {
                    sleep(pollingDelayMillis);
                }

                resetRetriesAttemptsCount();
            } catch (RedisConnectionFailureException e) {
                incrementRetriesAttemptsCount();
                log.warn(String.format("Connection failure during scheduler polling (attempt %s/%s)", numRetriesAttempted, maxRetriesOnConnectionFailure));
            }
        }

        private boolean isMaxRetriesAttemptsReached() {
            return numRetriesAttempted >= maxRetriesOnConnectionFailure;
        }

        private void resetRetriesAttemptsCount() {
            numRetriesAttempted = 0;
        }

        private void incrementRetriesAttemptsCount() {
            numRetriesAttempted++;
        }
    }
}
