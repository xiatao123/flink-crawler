package org.apache.flink.streaming.api.environment;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobSubmissionResult;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.instance.ActorGateway;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobStatus;
import org.apache.flink.runtime.messages.JobManagerMessages;
import org.apache.flink.runtime.messages.JobManagerMessages.CancellationFailure;
import org.apache.flink.runtime.messages.JobManagerMessages.CancellationSuccess;
import org.apache.flink.runtime.messages.JobManagerMessages.CurrentJobStatus;
import org.apache.flink.runtime.messages.JobManagerMessages.JobNotFound;
import org.apache.flink.runtime.minicluster.LocalFlinkMiniCluster;
import org.apache.flink.streaming.api.graph.StreamGraph;

import scala.concurrent.Await;
import scala.concurrent.Future;

/**
 * A modified version of LocalStreamEnvironment that supports executing a job
 * asynchronously.
 * 
 * FUTURE - better would be start()/stop() calls to start & stop the execution engine
 * (LocalFlinkMiniCluster), and then executeAsync() fails if it's not started,
 * and stop(job id) just stops the job.
 *
 */
public class LocalStreamEnvironmentWithAsyncExecution extends LocalStreamEnvironment {

	private Configuration _conf;
	private LocalFlinkMiniCluster _exec;
	
	public LocalStreamEnvironmentWithAsyncExecution() {
		this(new Configuration());
	}

	public LocalStreamEnvironmentWithAsyncExecution(Configuration config) {
		super(config);
		
		_conf = config;
	}
	
	/**
	 * This method lets you start a job and immediately return.
	 * 
	 * @param jobName
	 * @return
	 * @throws Exception
	 */
	public JobSubmissionResult executeAsync(String jobName) throws Exception {
		// transform the streaming program into a JobGraph
		StreamGraph streamGraph = getStreamGraph();
		streamGraph.setJobName(jobName);

		JobGraph jobGraph = streamGraph.getJobGraph();

		Configuration configuration = new Configuration();
		configuration.addAll(jobGraph.getJobConfiguration());

		configuration.setLong(TaskManagerOptions.MANAGED_MEMORY_SIZE, -1L);
		configuration.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, jobGraph.getMaximumParallelism());

		// add (and override) the settings with what the user defined
		configuration.addAll(_conf);

		_exec = new LocalFlinkMiniCluster(configuration, true);
		_exec.start(true);
		
		// The above code is all basically the same as Flink's LocalStreamEnvironment.
		// The change is that here we call submitJobDetached vs. submitJobAndWait.
		// We assume that eventually someone calls stop(job id), which then terminates
		// the LocalFlinkMinimCluster.
		return _exec.submitJobDetached(jobGraph);
	}
	
	/**
	 * Return whether <jobID> is currently running.
	 * 
	 * @param jobID
	 * @return true if running.
	 * @throws Exception
	 */
	public boolean isRunning(JobID jobID) throws Exception {
		ActorGateway leader = _exec.getLeaderGateway(_exec.timeout());
		Future<Object> response = leader.ask(new JobManagerMessages.RequestJobStatus(jobID), _exec.timeout());
		Object result = Await.result(response, _exec.timeout());
		if (result instanceof CurrentJobStatus) {
			JobStatus jobStatus = ((CurrentJobStatus)result).status();
			return !jobStatus.isGloballyTerminalState();
		} else if (response instanceof JobNotFound) {
			return false;
		} else {
			throw new RuntimeException("Unexpected response to job status: " + result);
		}
	}
	
	/**
	 * Stop the <jobID> job. This should be called even if isRunning() returns false,
	 * so that the LocalFlinkMiniCluster will be terminated.
	 * 
	 * @param jobID
	 * @throws Exception
	 */
	public void stop(JobID jobID) throws Exception {
		try {
			// Try to cancel the job.
			ActorGateway leader = _exec.getLeaderGateway(_exec.timeout());
			Future<Object> response = leader.ask(new JobManagerMessages.CancelJob(jobID), _exec.timeout());

			Object result = Await.result(response, _exec.timeout());
			if (result instanceof CancellationSuccess) {
				// All good.
			} else if (result instanceof CancellationFailure) {
				CancellationFailure failure = (CancellationFailure)result;
				throw new RuntimeException("Failure cancelling job", failure.cause());
			} else {
				throw new RuntimeException("Unexpected result of cancelling job: " + result);
			}
		} finally {
			transformations.clear();
			_exec.stop();
		}
	}

}