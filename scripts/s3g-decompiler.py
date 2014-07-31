#!/usr/bin/python
import struct
import sys

byteOffset = 0

toolCommandTable = {
    3: ("<H", "Set target temperature: %i"),
    4: ("B", "Set motor 1 speed (pwm): %i"),
    10: ("B", "Toggle motor 1: %d"),
    12: ("B", "Turn fan on (1) or off (0): %d"),
    13: ("B", "Toggle valve: %d"),
    27: ("B", "Toggle ABP: %d"),
    31: ("<H", "Set build platform target temperature: %i"),
    129: ("<iiiI","Absolute move: (%i,%i,%i) at DDA %i"),
}

def parseToolAction():
    global s3gFile
    global byteOffset
    packetStr = s3gFile.read(3)
    if len(packetStr) != 3:
        raise "Incomplete s3g file during tool command parse"
    byteOffset += 3
    (index,command,payload) = struct.unpack("<BBB",packetStr)
    contents = s3gFile.read(payload)
    if len(contents) != payload:
        raise "Incomplete s3g file: tool packet truncated"
    byteOffset += payload
    return (index,command,contents)

def printToolAction(tuple):
    print "Tool %i: " % (tuple[0]),
    # command - tuple[1]
    # data - tuple[2]
    (parse, disp) = toolCommandTable[tuple[1]]
    if type(parse) == type(""):
        packetLen = struct.calcsize(parse)
        if len(tuple[2]) != packetLen:
            raise "Packet incomplete"
        parsed = struct.unpack(parse,tuple[2])
    else:
        parsed = parse()
    if type(disp) == type(""):
        print disp % parsed

def parseDisplayMessageAction():
    global s3gFile
    global byteOffset
    packetStr = s3gFile.read(4)
    if len(packetStr) < 4:
        raise "Incomplete s3g file during tool command parse"
    byteOffset += 4
    (options,offsetX,offsetY,timeout) = struct.unpack("<BBBB",packetStr)
    message = "";
    while True:
       c = s3gFile.read(1);
       byteOffset += 1
       if c == '\0':
          break;
       else:
          message += c;

    return (options,offsetX,offsetY,timeout,message)

def parseBuildStartNotificationAction():
    global s3gFile
    global byteOffset
    packetStr = s3gFile.read(4)
    if len(packetStr) < 4:
        raise "Incomplete s3g file during tool command parse"
    byteOffset += 4
    (steps) = struct.unpack("<i",packetStr)
    buildName = "";
    while True:
       c = s3gFile.read(1);
       byteOffset += 1
       if c == '\0':
          break;
       else:
          buildName += c;

    return (steps[0],buildName)

# Command table entries consist of:
# * The key: the integer command code
# * A tuple:
#   * idx 0: the python struct description of the rest of the data,
#            of a function that unpacks the remaining data from the
#            stream
#   * idx 1: either a format string that will take the tuple of unpacked
#            data, or a function that takes the tuple as input and returns
#            a string
# REMINDER: all values are little-endian. Struct strings with multibyte
# types should begin with "<".
# For a refresher on Python struct syntax, see here:
# http://docs.python.org/library/struct.html
commandTable = {    
    129: ("<iiiI","Absolute move: (%i,%i,%i), DDA %i"),
    130: ("<iii","Set machine position: (%i,%i,%i)"),
    131: ("<BIH","Home minimum: on %X, feedrate %i, timeout %i s"),
    132: ("<BIH","Home maximum: on %X, feedrate %i, timeout %i s"),
    133: ("<I","Dwell: delay by %i us"),
    134: ("<B","Switch tool: change to tool %i"),
    135: ("<BHH","Wait on tool: wait for tool %i, %i ms between polls, %i s timeout"),
    136: (parseToolAction, printToolAction),
    137: ("<B", "Enable/disable axes: axes bitmask %X"),
    138: ("<H", "Wait on user response: option %i"),
    139: ("<iiiiiI","Absolute move: (%i,%i,%i,%i,%i), DDA %i"),
    140: ("<iiiii","Set machine position extended: (%i,%i,%i,%i,%i)"),
    141: ("<BHH","Wait on platform: tool %i, %i ms between polls, %i s timeout"),
    142: ("<iiiiiIB","Move: (%i,%i,%i,%i,%i) in %i us (relative: %X)"),
    143: ("<b","Store home position: axes bitmask %d"),
    144: ("<b","Recall home position: axes bitmask %d"),
    145: ("<BB","Set digipots: axis bitmask %i, digipot setting %i"),
    146: ("<BBBBB","Set RGB LED: red %i, green %i, blue %i, blink rate %i, effect %i"),
    147: ("<HHB","Set buzzer: frequency %i, length %i, effect %i"),
    148: ("<BHB","Pause for button: button 0x%X, timeout %i s, timeout_bevavior %i"),
    149: (parseDisplayMessageAction, "Display message: options 0x%X at %i,%i timeout %i s, message \"%s\""),
    150: ("<BB","Set build percentage: %i%%, ignore %i"),
    151: ("<B","Queue song: song number %i"),
    152: ("<B","Reset to factory defaults: options 0x%X"),
    153: (parseBuildStartNotificationAction, "Start build: steps %i, name \"%s\""),
    154: ("<B","End build: flags 0x%X"),
    155: ("<iiiiiIBfh","Move to: (%i,%i,%i,%i,%i), dda_rate %i, relative mask %X, distance %f, feedrateX64 %i"),
    156: ("<B","Set acceleration state: %i"),
    157: ("<BBBIHHIIB","Stream version: %i.%i, %i, %i, %i, %i, %i, %i, %i"),
    158: ("<f","Pause@ZPos: Z=%f"),
}

def parseNextCommand():
    """Parse and handle the next command.  Returns
    True for success, False on EOF, and raises an
    exception on corrupt data."""
    global s3gFile
    global byteOffset
    commandStr = s3gFile.read(1)
    if len(commandStr) == 0:
        print "EOF"
        return False
    sys.stdout.write(str(lineNumber) + ' [' + str(byteOffset) + ']: ')
    byteOffset += 1
    (command) = struct.unpack("B",commandStr)
    (parse, disp) = commandTable[command[0]]
    if type(parse) == type(""):
        packetLen = struct.calcsize(parse)
        packetData = s3gFile.read(packetLen)
        if len(packetData) != packetLen:
            raise "Packet incomplete"
        byteOffset += packetLen
        parsed = struct.unpack(parse,packetData)
    else:
        parsed = parse()
    if type(disp) == type(""):
        print disp % parsed
    else:
        disp(parsed)
    return True

s3gFile = open(sys.argv[1],'rb')
lineNumber = 1
sys.stdout.write('Command number [byte offset]: Command name: options\n')
while parseNextCommand():
    lineNumber  = lineNumber + 1
