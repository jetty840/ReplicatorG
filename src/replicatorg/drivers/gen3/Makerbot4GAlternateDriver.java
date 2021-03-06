package replicatorg.drivers.gen3;

import java.util.EnumMap;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Level;

import javax.vecmath.Point3d;

import org.w3c.dom.Element;

import replicatorg.app.Base;
import replicatorg.drivers.RetryException;
import replicatorg.machine.model.AxisId;
import replicatorg.machine.model.MachineModel;
import replicatorg.machine.model.ToolModel;
import replicatorg.util.Point5d;
import replicatorg.drivers.Version;

public class Makerbot4GAlternateDriver extends Makerbot4GDriver {
	
	private boolean stepperExtruderFanEnabled = false;

	public String getDriverName() {
		return "Makerbot4GAlternate";
	}
	
	public void reset() {
		// We should poll the machine for it's state here, but it is more important to have the
		// fan on than off.
		stepperExtruderFanEnabled = false;

		super.reset();
	}
	
	/** The excess, in steps, from previous operations. */ 
	protected Point5d stepExcess = new Point5d();
	
	/**
	 * Overloaded to manage a hijacked axis and run this axis in relative mode instead of the extruder DC motor
	 */
	public void queuePoint(Point5d p) throws RetryException {
		// If we don't know our current position, make this move an old-style move, ignoring any hijacked axes. 
		if (positionLost()) {
			Base.logger.fine("Position invalid, reverting to default speed for next motion");
			// Filter away any hijacked axes from the given point.
			Point5d filteredPoint = new Point5d(p);
			long longestDDA = 0;
			
			for (AxisId axis : getAllHijackedAxes()) {
				filteredPoint.setAxis(axis, 0d);
			}
			
			// Find the slowest DDA of all motion axes
			Point5d maxFeedrates = machine.getMaximumFeedrates();
			Point5d stepsPerMM = machine.getStepsPerMM();
			for (AxisId axis : machine.getAvailableAxes()) {
				long axisDDA = (long) (60*1000000/(maxFeedrates.axis(axis)*stepsPerMM.axis(axis)));
				
				Base.logger.info("For axis " + axis.getIndex()
						+ ", maxFeedrate="
						+ maxFeedrates.axis(axis)
						+ ", stepsPerMM=" + stepsPerMM.axis(axis)
						+ "DDA:" + axisDDA);
				
				if (axisDDA > longestDDA) {
					longestDDA = axisDDA;
				}
			}
			
			// okay, send it off!
			// TODO: bug: We move all axes (even ones that shouldn't be moved) How to avoid?
			Point5d excess = new Point5d(stepExcess);
			if ( absoluteXYZ ) { excess.setX(0); excess.setY(0); excess.setZ(0); }
			queueAbsolutePoint(machine.mmToSteps(filteredPoint, excess), longestDDA);
			if ( absoluteXYZ ) { excess.setX(0); excess.setY(0); excess.setZ(0); }
			// Only update excess if no retry was thrown.
			pastExcess = new Point5d(stepExcess);
			stepExcess = excess;
			// Finally, recored the position, and mark it as valid.
			setInternalPosition(filteredPoint);
		} else {
			// Filter away any hijacked axes from the given point.
			// This is necessary to avoid taking deltas into account where we 
			// compare the relative p coordinate (usually 0) with the absolute 
			// currentPosition (which we get from the Motherboard).
			Point5d filteredpoint = new Point5d(p);
			Point5d filteredcurrent = new Point5d(getCurrentPosition(false));
			Point5d excess = new Point5d();
			int relative = 0;
			if ( !absoluteXYZ ) excess = new Point5d(stepExcess);
			for (AxisId axis : getAllHijackedAxes()) {
				filteredpoint.setAxis(axis, 0d);
				filteredcurrent.setAxis(axis, 0d);
				relative |= 1 << axis.getIndex();
				excess.setAxis(axis, stepExcess.axis(axis)); // save accumulated roundoff for relative move
			}
		
			// is this point even step-worthy? Only compute nonzero moves
			Point5d deltaSteps = getAbsDeltaSteps(filteredcurrent, filteredpoint);
			if (deltaSteps.length() > 0.0) {
				Point5d delta = new Point5d();
				delta.sub(filteredpoint, filteredcurrent); // delta = p - current
				delta.absolute(); // absolute value of each component
				
				Point5d axesmovement = calcHijackedAxesMovement(delta);
				delta.add(axesmovement);
				filteredpoint.add(axesmovement);

				// Calculate time for move in usec
				Point5d steps = machine.mmToSteps(filteredpoint,excess);		

				// okay, send it off!
				// The 4. and 5. dimensions doesn't have a spatial interpretation. Calculate time in 3D space
				double minutes = delta.get3D().distance(new Point3d())/ getSafeFeedrate(delta);
				
				queueNewPoint(steps, (long) (60 * 1000 * 1000 * minutes), relative);

				// Only update excess if no retry was thrown.
				pastExcess = new Point5d(stepExcess);
				if ( absoluteXYZ) {
				    stepExcess = new Point5d();
				    for (AxisId axis : getAllHijackedAxes()) 
					stepExcess.setAxis(axis, excess.axis(axis)); // save roundoff for relative axes
				}
				else
				    stepExcess = excess;

				setInternalPosition(filteredpoint);
			}
		}
	}

	/**
	 * In legacy use, delayed while a DC motor ran. For compatiblity, this function
	 * creates a Point5D to do 'extrude while sitting still'. 
	 */
	public void delay(long millis) throws RetryException {
		Base.logger.finer("Delaying " + millis + " millis.");	
	
		Point5d steps = pointsFromHijackedAxes( machine.currentTool(), millis / 60000d);

		/// All axes relative to avoid dealing with absolute coords
		if (steps.length() > 0) {
			queueNewPoint(steps, millis * 1000, 0x1f); 
		}
		// This resulted in no stepper movements -> fall back to normal delay
		else {
			super.delay(millis); 
		}
	}
	
	/** 
	 * Each Axis can be overriden (hijacked) by XML settings. Return all overridden
	 * axes associated with tool curTool.
	 * @param curTool target tool to find overrides for
	 * @return a list of AxisId containing all overriden axis for tool curTool
	 */
	protected Iterable<AxisId> getHijackedAxes(ToolModel curTool) {
		//NOTE:
		/// can be reduced to '		axes.add(curTool.getMotorStepperAxis());' 
		/// once we get rid of curTool calls
		Vector<AxisId> axes = new Vector<AxisId>();
		for ( Map.Entry<AxisId,ToolModel> entry : extruderHijackedMap.entrySet()) {
			AxisId axis = entry.getKey();
			if (curTool.equals(entry.getValue())) {
				axes.add(axis);
			}
		}
		return axes;
	}

	
	/** 
	 * Each Axis can be overriden (hijacked) by XML settings. Return all overridden
	 * axes for the currently loaded machine.
	 * @return a list of AxisId containing all overriden axis for the loaded machine profile
	 */
	protected Iterable<AxisId> getAllHijackedAxes() {
		Vector<AxisId> axes = new Vector<AxisId>();

		for ( Map.Entry<AxisId,ToolModel> entry : extruderHijackedMap.entrySet()) {
			AxisId axis = entry.getKey();
			axes.add(axis);
		}
		return axes;
	}
	
	/**
	 * Calculate and return the corresponding movement of any hijacked axes where the extruder is on.
	 * The returned movement is in mm of incoming filament (corresponding to mm in machines.xml)
	 * If the extruder is off, the hijacked axes are not moved.
	 * @param delta relative XYZ movement.
	 * @return The relative movement (in mm) of the hijacked axes
	 */
	private Point5d calcHijackedAxesMovement(Point5d delta) {

		Point5d movement = new Point5d();
		double minutes = delta.length() / getCurrentFeedrate();

		for (AxisId axis : getHijackedAxes(machine.currentTool()) ) {
			ToolModel curTool = machine.currentTool();
			if (curTool.isMotorEnabled()) {
				double extruderStepsPerMinute = curTool.getMotorSpeedRPM() * curTool.getMotorSteps();
				final boolean clockwise = machine.currentTool().getMotorDirection() == ToolModel.MOTOR_CLOCKWISE;
				movement.setAxis(axis, extruderStepsPerMinute * minutes / machine.getStepsPerMM().axis(axis) * (clockwise?-1d:1d));
			}
		}
		return movement;
	}

	/**
	 * Generates a Point5D for extrusion based on if an axis is hijacked, if a motor is enabled
	 * and if the axis is created properly. 
	 * Write a relative movement to any axes which has been hijacked where the extruder is turned on.
	 * The axis will be moved with a length corresponding to the duration of the movement.
	 * The speed of the hijacked axis will be clamped to its maximum feedrate.
	 * If the extruder is off, the corresponding axes are set to a zero relative movement.
	 * @param curTool target tool to check for axis hijacking
	 * @param minutes extrusion time 
	 * @return 	If a the axis for curTool is 'hijacked' and enabled returns a Point5d for extruding for time set 
	 * by @minutes. If no axis is enabled and hijacked, returns an 'empty' Point5d with no motion.
	 */
	private Point5d pointsFromHijackedAxes(ToolModel curTool, double minutes) {
		int relative = 0;
		Point5d steps = new Point5d();
		Base.logger.finer("modify hijacked axes");
		
		for (AxisId axis : getHijackedAxes(machine.currentTool())) {
			Base.logger.finer("modify hijacked axes doing " + axis.toString() );
			relative |= 1 << axis.getIndex();
			double extruderSteps = 0;
			
			if (curTool.isMotorEnabled()) {
				Base.logger.finer("modify hijacked axes doing enabled stuff" + axis.toString() );
				double maxrpm = machine.getMaximumFeedrates().axis(axis) * machine.getStepsPerMM().axis(axis) / curTool.getMotorSteps();
				double rpm = (curTool.getMotorSpeedRPM() > maxrpm) ? maxrpm : curTool.getMotorSpeedRPM();
				boolean clockwise = machine.currentTool().getMotorDirection() == ToolModel.MOTOR_CLOCKWISE;
				extruderSteps = rpm * curTool.getMotorSteps() * minutes * (clockwise?-1d:1d);
			}
			
			Base.logger.finer("setting axis " + axis.toString() );
			Base.logger.finer("setting extruderSteps" + Double.toString(extruderSteps) );
			steps.setAxis(axis, extruderSteps);
		}
		return steps;
	}

	public void stop(boolean abort) {
		// Record the toolstate as off, so we don't excite the extruder motor in future moves.
		machine.currentTool().disableMotor();

		// We should stop the fan here, but it will be stopped for us by the super.
		stepperExtruderFanEnabled = false;

		super.stop(abort);
	}

	protected void queueNewPoint(Point5d steps, long us, int relative) throws RetryException {

		Base.logger.finer("Makerbot4GAlternateDriver queueNewPoint");

		// Turn on fan if necessary
		for (AxisId axis : getHijackedAxes( machine.currentTool()) ) {
			if (steps.axis(axis) != 0) {
				enableStepperExtruderFan(true);
			}
		}
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.QUEUE_POINT_NEW.getCode());

		Base.logger.finer("Queued new-style point " + steps + " over "
					+ Long.toString(us) + " usec., relative " + Integer.toString(relative));


		// just add them in now.
		pb.add32((int) steps.x());
		pb.add32((int) steps.y());
		pb.add32((int) steps.z());
		pb.add32((int) steps.a());
		pb.add32((int) steps.b());
		pb.add32((int) us);
		pb.add8((int) relative);

		runCommand(pb.getPacket());
	}

	protected void queueNewExtPoint(Point5d steps, long dda_rate, int relative, float distance, float feedrate) throws RetryException {

		Base.logger.finer("Makerbot4GAlternateDriver queueNewExtPoint");

		// Turn on fan if necessary
		for (AxisId axis : getHijackedAxes( machine.currentTool()) ) {
			if (steps.axis(axis) != 0) {
				enableStepperExtruderFan(true);
			}
		}
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.QUEUE_POINT_NEW_EXT.getCode());

		Base.logger.finer("Queued new-style extended point " + steps + " over "
					+ Long.toString(dda_rate) + " steps per sec., relative " + Integer.toString(relative)
					+ ", distance " + Float.toString(distance)
					+ ", feedrate " + Float.toString(feedrate));

		// just add them in now.
		pb.add32((int) steps.x());
		pb.add32((int) steps.y());
		pb.add32((int) steps.z());
		pb.add32((int) steps.a());
		pb.add32((int) steps.b());
		pb.add32((int) dda_rate);
		pb.add8((int) relative);
		pb.addFloat(distance);
		pb.add16((int) (feedrate * 64.0));

		runCommand(pb.getPacket());
	}
	
	/**
	 * Overridden to not talk to the DC motor driver. This driver is reused for the stepper motor fan
	 */
	public void enableMotor() throws RetryException {
		machine.currentTool().enableMotor();
	}
	
	/**
	 * Overridden to not talk to the DC motor driver. This driver is reused for the stepper motor fan
	 */
	public void disableMotor() throws RetryException {
		machine.currentTool().disableMotor();
	}
	
	/**
	 * Overridden to not talk to the DC motor driver. This driver is reused for the stepper motor fan
	 */
	public void setMotorSpeedPWM(int pwm) throws RetryException {
		machine.currentTool().setMotorSpeedPWM(pwm);
	}

	/**
	 * Overridden to not talk to the DC motor driver. This driver is reused for the stepper motor fan
	 */
	public void setMotorRPM(double rpm, int toolhead) throws RetryException {
		machine.currentTool().setMotorSpeedRPM(rpm);
	}

	public void enableDrives() throws RetryException {
		enableStepperExtruderFan(true);
		
		super.enableDrives();
	}

	public void disableDrives() throws RetryException {
		enableStepperExtruderFan(false);
		
		super.disableDrives();
	}
	
	/**
	 * Will turn on/off the stepper extruder fan if it's not already in the correct state.
	 * 
	 */
	public void enableStepperExtruderFan(boolean enabled) throws RetryException {
		
		// Always re-enable the fan when 
		if (this.stepperExtruderFanEnabled == enabled) return;
		
		// FIXME: Should be called per hijacked axis with the correct tool
		// our flag variable starts with motors enabled.
		byte flags = (byte) (enabled ? 1 : 0);

		// bit 1 determines direction...
		flags |= 2;

		Base.logger.log(Level.FINE,"Stepper Extruder fan w/flags: "
					+ Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		// Always use max PWM
		pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_MOTOR_1_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) 255);
		runCommand(pb.getPacket());
		
		this.stepperExtruderFanEnabled = enabled;
	}


	/**
	 * Turn acceleration on/off for subsequent commands
	 */
        public void setAccelerationToggle(boolean on) throws RetryException {
                Base.logger.finer("SetAccelerationToggle (" + on + ")" );

                PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.SET_ACCELERATION_TOGGLE.getCode());

		if ( on )	pb.add8(1);
		else		pb.add8(0);

                runCommand(pb.getPacket());
        }

	/**
	 * Turn acceleration on/off for subsequent commands
	 */
        public void pauseAtZPos(double zpos) throws RetryException {
                Base.logger.finer("PauseAtZPos (" + zpos + ")" );

                PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.PAUSE_AT_ZPOS.getCode());

		pb.addFloat((float)zpos);

                runCommand(pb.getPacket());
        }

	/// This is a list of which axis are hijacked for extruder use.
	EnumMap<AxisId,ToolModel> extruderHijackedMap = new EnumMap<AxisId,ToolModel>(AxisId.class);
	
	@Override
	/**
	 * When the machine is set for this driver, some toolheads may poach the an extrusion axis.
	 */
	public void setMachine(MachineModel m) {
		super.setMachine(m);
		for (ToolModel tm : m.getTools()) {
			Element e = (Element)tm.getXml();
			if (e.hasAttribute("stepper_axis")) {
				final String stepAxisStr = e.getAttribute("stepper_axis");
				try {
					AxisId axis = AxisId.valueOf(stepAxisStr.toUpperCase());
					if (m.hasAxis(axis)) {
						Base.logger.finer("seize the axis for extrusion!  Hijacking axis "+axis.name());
						// If we're seizing an axis for an extruder, remove it from the available axes and get
						// the data associated with that axis.
						extruderHijackedMap.put(axis,tm);
						m.getAvailableAxes().remove(axis);
					} else {
						Base.logger.severe("Tool claims unavailable axis "+axis.name());
					}
				} catch (IllegalArgumentException iae) {
					Base.logger.severe("Unintelligible axis designator "+stepAxisStr);
				}
			}
		}
	}
	
	@Override
	/**
	 * Overridden to not ask the board for the RPM as it would report the RPM from the extruder 
	 * controller, which doesn't know about it in this case.
	 */
	public double getMotorRPM() {
		double rpm = machine.currentTool().getMotorSpeedRPM();
		machine.currentTool().setMotorSpeedReadingRPM(rpm);
		return rpm;
	}
	
	@Override 
	public String getMachineType(){ return "Thing-O-Matic/CupCake CNC"; } 
}
