package replicatorg.drivers.commands;

import replicatorg.drivers.Driver;
import replicatorg.drivers.RetryException;

public class PauseAtZPos implements DriverCommand {

	double zpos;

	public PauseAtZPos(double zpos) {
		this.zpos = zpos;
	}

	@Override
	public void run(Driver driver) throws RetryException {
		driver.pauseAtZPos(zpos);
	}
}
