/*******************************************************************************
 *  	Copyright 2014,
 *  		Luis Pina <luis@luispina.me>,
 *  		Michael Hicks <mwh@cs.umd.edu>
 *
 *  	This file is part of Rubah.
 *
 *     Rubah is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Rubah is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Rubah.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package rubah.runtime.state;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import rubah.Rubah;
import rubah.RubahException;
import rubah.RubahThread;
import rubah.io.RubahIO;
import rubah.runtime.RubahRuntime;
import rubah.runtime.state.UpdateState.StoppedThread;

public class ObservedStoppingThreads extends RubahState {
	private long	time;
	private States	states;

	private Lock stateLock = new ReentrantLock();
	private Condition runningChanged = this.stateLock.newCondition();

	public ObservedStoppingThreads(UpdateState state, States states) {
		super(state);
		this.states = states;
	}

	@Override
	public RubahState start() {
		this.time = System.currentTimeMillis();
		this.state.setUpdateTime(time);
		// Interrupt threads waiting on IO
		RubahIO.interruptThreads();

		Rubah.getOut().print("Waiting for threads ");
		this.state.printRunningThreads();
		// This code is executed by an application thread
		// Return null to stop that, the updater thread will call method restart after
		return null;
	}

	public void restart() {
		this.stateLock.lock();
		try {
			while(!this.state.getRunning().isEmpty()) {
				this.state.printRunningThreads();
				try {
					this.runningChanged.await();
				} catch (InterruptedException e) {
					continue;
				}
			}

			time = System.currentTimeMillis() - time;
			Rubah.getOut().println("Stopped " + this.state.getStopped().size() + " threads in " + time + "ms");
			this.state.setStates(this.states);
			RubahRuntime.changeState(states.moveToNextState());
		} finally {
			this.stateLock.unlock();
		}
	}

	@Override
	public void deregisterRunningThread(RubahThread t, RubahException e) {
		this.stateLock.lock();
		try {
			super.deregisterRunningThread(t,e);

			if (e == null) {
				return; // Thread did not stop in update point
			}

			this.state.getStopped().add(new StoppedThread(Thread.currentThread(), t, e.getUpdatePoint(), t.getName()));
			this.runningChanged.signalAll();
		} finally {
			this.stateLock.unlock();
		}
	}

	@Override
	public void update(String updatePoint) {
		Rubah.getOut().println("Thread " + Thread.currentThread() + " reached update point \"" + updatePoint + "\"");
		throw new RubahException(updatePoint);
	}

	@Override
	public boolean isUpdating() {
		return false;
	}

	@Override
	public boolean isUpdateRequested() {
		return true;
	}

	@Override
	protected void doStart() {
		throw new Error("Should never be called");
	}
}
