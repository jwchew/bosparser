BOS Parser updated code, works for modern Il-2 Sturmovik: Battle of Stalingrad.

Updated June 17, 2025 by jwchew.

== Motivation for update ==
I came across the original bosparser written by erikuphander (Lupson on the Il-2 forums) but the code had not been updated since 2015, and it seemed like subsequent Il-2 updates had slightly changed the format of the Il-2 BOS mission log files again, breaking the program. I went into the source code that was uploaded to Github to see if I could figure out how to restore the program's function again.

== The issue ==
When I tried running the most updated bosparser package (last updated in 2015), I would get the following error message when I tried to scan for reports from the web GUI:

Scan result
{"timestamp":1749940945714,"status":500,"error":"Internal Server Error","exception":"java.lang.NumberFormatException","message":"For input string: \"-1 POS(98894.3438,1499.1138,144793.9063)\"","path":"/rest/view/reports"}

It turns out that in the file Parser.java, the portion of code that pulls out the parent ID does so by finding the substring "PID" in the log file and taking everything afterwards to the end of line as the parent ID. This code used to work back when Il-2 had omitted position data from the mission log file and the PID was the last item on the line. However, Il-2 has added position data back into the log files, so the position data gets included as "PID".

See below for an example of the line that would trip up the old code:

T:15 AType:12 ID:41983 TYPE:La-5 ser.8 COUNTRY:101 NAME:La-5 ser.8 PID:-1 POS(98894.3438,1499.1138,144793.9063)

I updated Parser.java to only take the PID and to discard the position data afterwards, which fixed the issue.

== Important info for compiling the code ==
To make things simple for myself, I downloaded versions of JDK and Maven from 2015 (around the time of the last Github commit in the original bosparser) to make sure the code would compile correctly. My initial attempt to use modern Java and Maven led me down a rabbit hole of incompatibilities, so I would recommend others do the same unless you want to try to update the source code to be compatible with modern packages.

For reference, I used the following:
  - jdk-8u441
  - maven 3.2.5

In addition, it looked like erikuphander began adding additional classes to further expand functionality, but this was unfinished. There are variables in his code that are not properly defined that will throw errors during compiling, specifically in MissionDataServiceBean.java. I simply deleted this file and was then able to compile without issues.

== Outstanding issues ==
The map functionality is still broken, and it seems that flight position data is still missing from the mission logs.

== Running the updated program ==
To run the updated program, download the updated compiled package and simply run bosparser.bat in the same way as the original bosparsper per erikuphander's original instructions.