import java.io.*;
import java.util.*;

public class Main {

    static class SchedulerMonitor {
        private int currentTime = 0;
        private ProcessThread runningProcess = null;
        private boolean sliceFinished = false;

        public synchronized int getCurrentTime() {
            return currentTime;
        }

        public synchronized void advanceTime(int dt) {
            currentTime += dt;
        }

        public synchronized void dispatch(ProcessThread p) {
            runningProcess = p;
            sliceFinished = false;
            notifyAll();
        }

        public synchronized void waitUntilScheduled(ProcessThread p) throws InterruptedException {
            while (runningProcess != p) {
                wait();
            }
        }

        public synchronized void notifySliceFinished() {
            runningProcess = null;
            sliceFinished = true;
            notifyAll();
        }

        public synchronized void waitForSliceFinished() throws InterruptedException {
            while (!sliceFinished) {
                wait();
            }
        }
    }

    static class ProcessThread extends Thread {
        int pid;
        int arrivalTime;
        int burstTime;
        int remainingTime;

        boolean started = false;
        boolean finished = false;

        int waitingTime = 0;
        int readyQueueEnterTime;

        int assignedQuantum = 1;

        SchedulerMonitor monitor;
        PrintWriter writer;

        public ProcessThread(int pid, int arrivalTime, int burstTime,
                             SchedulerMonitor monitor, PrintWriter writer) {
            this.pid = pid;
            this.arrivalTime = arrivalTime;
            this.burstTime = burstTime;
            this.remainingTime = burstTime;
            this.monitor = monitor;
            this.writer = writer;
            this.readyQueueEnterTime = arrivalTime;
        }

        public synchronized void setAssignedQuantum(int q) {
            assignedQuantum = q;
        }

        private void log(String event, int time) {
            synchronized (writer) {
                writer.println("Time " + time + ", Process " + pid + ", " + event);
                writer.flush();
            }
        }

        @Override
        public void run() {
            try {
                while (!finished) {
                    monitor.waitUntilScheduled(this);

                    int startTime = monitor.getCurrentTime();

                    // waiting time += time spent in ready queue since last time it became ready
                    waitingTime += (startTime - readyQueueEnterTime);

                    if (!started) {
                        started = true;
                        log("Started", startTime);
                    }

                    log("Resumed", startTime);

                    int runTime;
                    synchronized (this) {
                        runTime = Math.min(assignedQuantum, remainingTime);
                    }

                    remainingTime -= runTime;
                    monitor.advanceTime(runTime);

                    int endTime = monitor.getCurrentTime();

                    log("Paused", endTime);

                    if (remainingTime == 0) {
                        log("Finished", endTime);
                        finished = true;
                    } else {
                        readyQueueEnterTime = endTime;
                    }

                    monitor.notifySliceFinished();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static class SchedulerThread extends Thread {
        List<ProcessThread> processes;
        SchedulerMonitor monitor;
        Integer lastScheduledPid = null;

        public SchedulerThread(List<ProcessThread> processes, SchedulerMonitor monitor) {
            this.processes = processes;
            this.monitor = monitor;
        }

        private boolean allFinished() {
            for (ProcessThread p : processes) {
                if (!p.finished) return false;
            }
            return true;
        }

        private List<ProcessThread> getReadyProcesses(int currentTime) {
            List<ProcessThread> ready = new ArrayList<>();
            for (ProcessThread p : processes) {
                if (!p.finished && p.arrivalTime <= currentTime) {
                    ready.add(p);
                }
            }
            return ready;
        }

        private int quantumFor(ProcessThread p) {
            // Strict assignment rule:
            // quantum = 10% of remaining execution time
            // keep minimum 1 so progress always happens
            return Math.max(1, (int) Math.ceil(p.remainingTime * 0.10));
        }

        private Comparator<ProcessThread> shortestRemainingComparator() {
            return (a, b) -> {
                if (a.remainingTime != b.remainingTime) {
                    return Integer.compare(a.remainingTime, b.remainingTime);
                }
                if (a.arrivalTime != b.arrivalTime) {
                    return Integer.compare(a.arrivalTime, b.arrivalTime);
                }
                return Integer.compare(a.pid, b.pid);
            };
        }

        private ProcessThread selectNextProcess(List<ProcessThread> ready) {
            ready.sort(shortestRemainingComparator());

            ProcessThread best = ready.get(0);

            // Fairness rule:
            // if the same process that just ran is again the shortest,
            // and there are other ready processes already in the queue,
            // allow one of those other ready processes to go first.
            if (lastScheduledPid != null && ready.size() > 1 && best.pid == lastScheduledPid) {
                List<ProcessThread> others = new ArrayList<>();
                for (ProcessThread p : ready) {
                    if (p.pid != lastScheduledPid) {
                        others.add(p);
                    }
                }
                if (!others.isEmpty()) {
                    others.sort(shortestRemainingComparator());
                    return others.get(0);
                }
            }

            return best;
        }

        @Override
        public void run() {
            try {
                while (!allFinished()) {
                    int now = monitor.getCurrentTime();
                    List<ProcessThread> ready = getReadyProcesses(now);

                    if (ready.isEmpty()) {
                        monitor.advanceTime(1);
                        continue;
                    }

                    ProcessThread next = selectNextProcess(ready);
                    next.setAssignedQuantum(quantumFor(next));

                    lastScheduledPid = next.pid;

                    monitor.dispatch(next);
                    monitor.waitForSliceFinished();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        List<ProcessThread> processes = new ArrayList<>();
        SchedulerMonitor monitor = new SchedulerMonitor();
        PrintWriter writer = null;

        try {
            BufferedReader br = new BufferedReader(new FileReader("input.txt"));
            writer = new PrintWriter(new FileWriter("output.txt"));

            String line;
            int pid = 1;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                int arrival = Integer.parseInt(parts[0]);
                int burst = Integer.parseInt(parts[1]);

                processes.add(new ProcessThread(pid, arrival, burst, monitor, writer));
                pid++;
            }
            br.close();

            for (ProcessThread p : processes) {
                p.start();
            }

            SchedulerThread scheduler = new SchedulerThread(processes, monitor);
            scheduler.start();

            scheduler.join();

            for (ProcessThread p : processes) {
                p.join();
            }

            writer.println("-----------------------------");
            writer.println("Waiting Times:");
            for (ProcessThread p : processes) {
                writer.println("Process " + p.pid + ": " + p.waitingTime);
            }

            writer.flush();
            writer.close();

            System.out.println("Simulation finished. Check output.txt");

        } catch (Exception e) {
            e.printStackTrace();
            if (writer != null) {
                writer.close();
            }
        }
    }
}
