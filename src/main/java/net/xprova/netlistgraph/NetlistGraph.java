package net.xprova.netlistgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import net.xprova.graph.Graph;
import net.xprova.graph.MultiMap;
import net.xprova.graph.Pair;
import net.xprova.netlist.Module;
import net.xprova.netlist.Net;
import net.xprova.netlist.Netlist;
import net.xprova.netlist.PinConnection;
import net.xprova.netlist.PinDirection;
import net.xprova.netlist.Port;

public class NetlistGraph extends Graph<Vertex> {

	private final String strHier = "\\%s.%s";

	private String name;

	private HashSet<Vertex> modules, nets, inputs, outputs;

	private MultiMap<Vertex, Vertex, String> pins; // pins are graph edges

	// modConnections: keys are module and port, value is connected net

	private MultiMap<Vertex, String, Vertex> modConnections;

	private ArrayList<String> orderedPorts; // as in header

	public NetlistGraph() {

		super();

		modules = new HashSet<Vertex>();

		nets = new HashSet<Vertex>();

		inputs = new HashSet<Vertex>();

		outputs = new HashSet<Vertex>();

		pins = new MultiMap<Vertex, Vertex, String>();

		modConnections = new MultiMap<Vertex, String, Vertex>();

		name = "undefined";

	}

	public NetlistGraph(Netlist netlist) throws Exception {

		this();

		name = netlist.name;

		// netMap: used to access Net vertices by their String name

		HashMap<String, Vertex> netMap = new HashMap<String, Vertex>();

		orderedPorts = new ArrayList<String>(netlist.orderedPorts);

		// first, add nets

		for (Net net : netlist.nets.values()) {

			for (int i = net.getLower(); i <= net.getHigher(); i++) {

				String vName = getNetVertexName(net.id, i, net.getCount());

				String type = "wire";

				Port p = netlist.ports.get(net.id);

				if (p != null) {

					if (p.direction == PinDirection.IN) {

						type = "input";

					} else if (p.direction == PinDirection.OUT) {

						type = "output";

					} else {

						throw new ConnectivityException("cannot determine direction of port " + net.id);

					}
				}

				Vertex v = new Vertex(vName, VertexType.NET, type);

				v.arrayIndex = i;

				v.arrayName = net.id;

				v.arraySize = net.getCount();

				addVertex(v);

				netMap.put(vName, v);
			}

		}

		// now add modules

		// map1 is used to map each Vertex to its corresponding Module object

		HashMap<Vertex, Module> map1 = new HashMap<Vertex, Module>();

		for (Module module : netlist.modules.values()) {

			Vertex v = new Vertex(module.id, VertexType.MODULE, module.type);

			addVertex(v);

			map1.put(v, module);
		}

		// connections

		for (Vertex module : modules) {

			Module mod = map1.get(module);

			Set<String> ports = mod.connections.keySet();

			for (String port : ports) {

				PinConnection con = mod.connections.get(port);

				int netBits = netlist.nets.get(con.net).getCount();

				String netName = getNetVertexName(con.net, con.bit, netBits);

				Vertex netV = netMap.get(netName);

				if (con.dir == PinDirection.IN) {

					addConnection(netV, module);

					pins.put(netV, module, port);

				} else if (con.dir == PinDirection.OUT) {

					addConnection(module, netV);

					pins.put(module, netV, port);

				}

				modConnections.put(module, port, netV);
			}
		}

		// inputs and outputs

		for (Port port : netlist.ports.values()) {

			for (int i = port.getLower(); i <= port.getHigher(); i++) {

				String netName = getNetVertexName(port.id, i, port.getCount());

				Vertex v = netMap.get(netName);

				if (port.direction == PinDirection.IN) {

					inputs.add(v);

				} else {

					outputs.add(v);
				}
			}

		}

		checkDrivers();

	}

	public NetlistGraph(NetlistGraph other) {

		super();

		// create map to keep track of vertex correspondences between this and
		// other

		HashMap<Vertex, Vertex> corr = new HashMap<Vertex, Vertex>();

		// clone name

		name = other.name;

		// clone vertices

		// (Initialise modules and nets since these are populated by
		// NetlistGraph addVertex)

		modules = new HashSet<Vertex>();

		nets = new HashSet<Vertex>();

		for (Vertex ov : other.vertices) {

			Vertex v = new Vertex(ov);

			this.addVertex(v);

			corr.put(ov, v);

		}

		// clone sources and destinations

		for (Vertex ov : other.vertices) {

			Vertex v = corr.get(ov);

			HashSet<Vertex> srcSet = new HashSet<Vertex>();
			HashSet<Vertex> dstSet = new HashSet<Vertex>();

			for (Vertex osrc : other.getSources(ov))
				srcSet.add(corr.get(osrc));

			for (Vertex odst : other.getDestinations(ov))
				dstSet.add(corr.get(odst));

			sources.put(v, srcSet);
			destinations.put(v, dstSet);

		}

		// clone hashsets inputs and outputs

		inputs = new HashSet<Vertex>();
		outputs = new HashSet<Vertex>();

		// for (Vertex ov : other.modules)
		// modules.add(corr.get(ov));
		//
		// for (Vertex ov : other.nets)
		// nets.add(corr.get(ov));

		for (Vertex ov : other.inputs)
			inputs.add(corr.get(ov));

		for (Vertex ov : other.outputs)
			outputs.add(corr.get(ov));

		// clone pins

		pins = new MultiMap<Vertex, Vertex, String>();

		for (Pair<Pair<Vertex, Vertex>, String> entry : other.pins.entrySet()) {

			Vertex ov1 = entry.first.first;
			Vertex ov2 = entry.first.second;

			Vertex v1 = corr.get(ov1);
			Vertex v2 = corr.get(ov2);

			String s = entry.second;

			pins.put(v1, v2, s);

		}

		// clone modConnections

		modConnections = new MultiMap<Vertex, String, Vertex>();

		for (Pair<Pair<Vertex, String>, Vertex> entry : other.modConnections.entrySet()) {

			Vertex ov1 = entry.first.first;
			Vertex ov2 = entry.second;

			Vertex v1 = corr.get(ov1);
			Vertex v2 = corr.get(ov2);

			String s = entry.first.second;

			modConnections.put(v1, s, v2);

		}

		// clone orderedPorts

		orderedPorts = new ArrayList<String>();

		for (String s : other.orderedPorts)
			orderedPorts.add(s);

	}

	private void checkDrivers() throws ConnectivityException {

		// checks graph for nets with no or multiple drivers

		final String msg_2 = "net <%s> has multiple drivers";

		TreeSet<Vertex> undriven = new TreeSet<Vertex>();

		// add special nets

		for (Vertex net : nets) {

			int drivers = sources.get(net).size();

			if (drivers == 0 && !inputs.contains(net)) {

				undriven.add(net);

			} else if (drivers > 1) {

				String msg = String.format(msg_2, net.name);

				throw new ConnectivityException(msg);
			}
		}

		// remove undriven nets

		System.out.flush();

		for (Vertex v : undriven) {

			System.out.printf("Warning: net %s has no driver\n", v);

			removeVertex(v);
		}

	}

	private String getNetVertexName(String id, int bit, int totalBits) {

		return totalBits > 1 ? id + "[" + bit + "]" : id;
	}

	public HashSet<Vertex> getNets() {

		// returns a shallow copy of nets

		return new HashSet<Vertex>(nets);
	}

	public HashSet<Vertex> getIONets() {

		// returns input and output net vertices

		HashSet<Vertex> ios = new HashSet<Vertex>();

		for (Vertex f : nets) {

			if ("input".equals(f.subtype) || "output".equals(f.subtype)) {

				ios.add(f);

			}
		}

		return ios;
	}

	public HashSet<Vertex> getModules() {

		// returns a shallow copy of modules

		return new HashSet<Vertex>(modules);
	}

	public HashSet<Vertex> getModulesByType(String type) {

		HashSet<Vertex> result = new HashSet<Vertex>();

		for (Vertex v : modules)
			if (v.subtype.equals(type))
				result.add(v);

		return result;

	}

	public HashSet<Vertex> getModulesByTypes(Set<String> types) {

		HashSet<Vertex> result = new HashSet<Vertex>();

		for (Vertex v : modules)
			if (types.contains(v.subtype))
				result.add(v);

		return result;

	}

	public String getPinName(Vertex v1, Vertex v2) {

		return pins.get(v1, v2);
	}

	public Vertex getNet(Vertex module, String port) {

		return modConnections.get(module, port);

	}

	@Override
	public boolean removeVertex(Vertex v) {

		if (super.contains(v)) {

			modules.remove(v);

			nets.remove(v);

			inputs.remove(v);

			outputs.remove(v);

			for (Vertex source : sources.get(v)) {

				String port = pins.get(source, v);

				modConnections.remove(v, port);

				pins.remove(source, v);

			}

			for (Vertex destination : destinations.get(v)) {

				String port = pins.get(v, destination);

				modConnections.remove(v, port);

				pins.remove(v, destination);

			}

			super.removeVertex(v);

			return true;

		} else {

			return false;

		}

	}

	@Override
	public boolean addVertex(Vertex v) {

		if (super.addVertex(v)) {

			if (v.type == VertexType.MODULE) {

				modules.add(v);

			} else if (v.type == VertexType.NET) {

				nets.add(v);

			}

			return true;

		} else {

			return false;

		}

	}

	@Override
	public boolean removeConnection(Vertex source, Vertex destination) {

		if (super.removeConnection(source, destination)) {

			String port = pins.get(source, destination);

			pins.remove(source, destination);

			if (source.type == VertexType.MODULE) {

				modConnections.remove(source, port);
			}

			if (destination.type == VertexType.MODULE) {

				modConnections.remove(destination, port);
			}

			return true;

		} else {

			return false;
		}

	}

	public boolean addConnection(Vertex source, Vertex destination, String port) throws Exception {

		if (destination.type == VertexType.NET && !getSources(destination).isEmpty()) {

			throw new Exception("net <" + destination + "> already has a driver " + getSources(destination)
					+ ", cannot add source " + source);

		}

		super.addConnection(source, destination);

		pins.put(source, destination, port);

		if (source.type == VertexType.MODULE) {

			modConnections.put(source, port, destination);
		}

		if (destination.type == VertexType.MODULE) {

			modConnections.put(destination, port, source);
		}

		return true;

	}

	@Override
	public Vertex getVertex(String name) {

		for (Vertex v : vertices) {

			if (v.name.equals(name))
				return v;
		}

		return null;

	}

	public String getPortList() {

		return join(orderedPorts, ", ");
	}

	public Graph<Vertex> getModuleGraph() {

		return reduce(modules);
	}

	public Graph<Vertex> getNetGraph() {

		return reduce(nets);
	}

	public Graph<Vertex> getModuleTypeGraph(String type) {

		HashSet<Vertex> cells = new HashSet<Vertex>();

		for (Vertex v : modules) {

			if (type.equals(v.subtype))
				cells.add(v);

		}

		return reduce(cells);
	}

	public Vertex getSourceModule(Vertex net) throws Exception {

		if (net.type != VertexType.NET) {

			throw new Exception("not a net");
		}

		return getSources(net).iterator().next();

	}

	@Override
	public NetlistGraph getSubGraph(HashSet<Vertex> subVertices) throws Exception {

		NetlistGraph subgraph = new NetlistGraph();

		for (Vertex vertex : subVertices) {

			subgraph.addVertex(vertex);
		}

		for (Vertex source : subVertices) {

			HashSet<Vertex> destinations = getDestinations(source);

			destinations.retainAll(subVertices);

			for (Vertex d : destinations) {

				subgraph.addVertex(d);

				if (isConnected(source, d)) {

					String port = this.getPinName(source, d);

					subgraph.addConnection(source, d, port);

				}

			}

		}

		return subgraph;

	}

	private static String join(ArrayList<String> list, String delim) {

		if (list.isEmpty()) {

			return "";

		} else if (list.size() == 1) {

			return list.get(0);

		} else {

			StringBuilder sb = new StringBuilder(1024);

			sb.append(list.get(0));

			for (int i = 1; i < list.size(); i++) {

				sb.append(delim);

				sb.append(list.get(i));

			}

			return sb.toString();

		}

	}

	public void addPort(String portName) {

		orderedPorts.add(portName);

	}

	public String getName() {

		return name;

	}

	public HashSet<Vertex> getInputs() {

		return new HashSet<Vertex>(inputs);

	}

	public HashSet<Vertex> getOutputs() {

		return new HashSet<Vertex>(outputs);

	}

	public HashMap<Vertex, Vertex> expand(Vertex module, NetlistGraph subGraphOrg) throws Exception {

		NetlistGraph subGraph = new NetlistGraph(subGraphOrg);

		HashSet<Vertex> modInputs = getSources(module);

		HashSet<Vertex> modOutputs = getDestinations(module);

		HashMap<String, Vertex> subInputs = new HashMap<String, Vertex>();

		HashMap<String, Vertex> subOutputs = new HashMap<String, Vertex>();

		for (Vertex v : subGraph.getInputs())
			subInputs.put(v.name, v);

		for (Vertex v : subGraph.getOutputs())
			subOutputs.put(v.name, v);

		// check matching in IO connections

		for (Vertex mi : modInputs) {

			String pin = getPinName(mi, module);

			if (!subInputs.containsKey(pin)) {

				String strE = String.format("module port <%s> not defined as input in sub-graph", mi.name);

				throw new Exception(strE);
			}

		}

		for (Vertex mo : modOutputs) {

			String pin = getPinName(module, mo);

			if (!subOutputs.containsKey(pin)) {

				String strE = String.format("module does not have <%s> input as in sub-graph", mo.name);

				throw new Exception(strE);
			}

		}

		// include subgraph

		HashMap<Vertex, Vertex> corr = include(subGraph);

		// remove subGraph io nets from this graph's io sets

		for (Vertex i : subGraph.inputs)
			inputs.remove(corr.get(i));

		for (Vertex o : subGraph.inputs)
			outputs.remove(corr.get(o));

		// merge subgraph input and output nets with their correspondents

		for (Vertex i : subGraph.inputs) {

			Vertex n1 = getNet(module, i.name);

			Vertex n2 = corr.get(i);

			joinNets(n1, n2, n1);

		}

		for (Vertex o : subGraph.outputs) {

			Vertex n1 = corr.get(o);
			Vertex n2 = getNet(module, o.name);

			// note: n2 may be null if output port o is not used

			// in this case we create a dummy node to connect to

			if (n2 == null) {

				String n2Name = "unused" + getNets().size();

				n2 = new Vertex(n2Name, VertexType.NET, "WIRE");

				addVertex(n2);

			}

			joinNets(n1, n2, n2);

		}

		// rename added nets and modules

		HashSet<Vertex> nonIO = subGraph.getNets();

		nonIO.removeAll(subGraph.getIONets());

		for (Vertex ns : nonIO) {

			Vertex n = corr.get(ns);

			n.name = String.format(strHier, module.name, n.name);

		}

		for (Vertex ms : subGraph.modules) {

			Vertex n = corr.get(ms);

			n.name = String.format(strHier, module.name, n.name);

		}

		// finally, remove module (this automatically removes all of its
		// connections)

		removeVertex(module);

		// return corr to parent for further manipulation of added graph (if
		// needed)

		return corr;

	}

	private void joinNets(Vertex n1, Vertex n2, Vertex preserve) throws Exception {

		//@formatter:off
		//
		// joinNets() is an algorithm used during expand().
		//
		// It combines two nets n1 and n2 into one by:
		// - joining their outgoing gate connections
		// - keeping the source connection of n1
		// - keeping whichever of n1/n2 equals `preserve` and removing the other
		//
		// For example, assume we have a graph where a net A is driven by gate x
		// and is an input for two gates y and z:
		//
		// x --> A --> y
		//       |---> z
		//
		// and (in the same graph) another net B is driven by gate k and is an
		// input for a gate w:
		//
		// k --> B --> w
		//
		// Now after executing joinNets(A, B, A), the graph will become:
		//
		// x --> A --> y
		//       | --> z
		//       | --> w
		//
		//@formatter:on

		if (preserve == n1) {

			// copy n2's connections to n1 and remove n2

			for (Vertex d : getDestinations(n2)) {

				String pin = getPinName(n2, d);

				addConnection(n1, d, pin);

			}

			if (inputs.contains(n2))
				inputs.add(n1);

			removeVertex(n2);

		} else if (preserve == n2) {

			// copy n1's connections to n2

			for (Vertex d : getDestinations(n1)) {

				String pin = getPinName(n1, d);

				addConnection(n2, d, pin);

			}

			// make n2 driven by n1's driver

			Vertex n1Driver = getSourceModule(n1);

			String port = getPinName(n1Driver, n1);

			removeConnection(getSourceModule(n2), n2);

			addConnection(n1Driver, n2, port);

			// remove n1

			removeVertex(n1);

		}

	}

	private HashMap<Vertex, Vertex> include(NetlistGraph other) {

		// create map to keep track of vertex correspondences between this and
		// other

		HashMap<Vertex, Vertex> corr = new HashMap<Vertex, Vertex>();

		// clone vertices

		for (Vertex ov : other.vertices) {

			Vertex v = new Vertex(ov);

			this.addVertex(v);

			corr.put(ov, v);

		}

		// clone sources and destinations

		for (Vertex ov : other.vertices) {

			Vertex v = corr.get(ov);

			HashSet<Vertex> srcSet = new HashSet<Vertex>();
			HashSet<Vertex> dstSet = new HashSet<Vertex>();

			for (Vertex osrc : other.getSources(ov))
				srcSet.add(corr.get(osrc));

			for (Vertex odst : other.getDestinations(ov))
				dstSet.add(corr.get(odst));

			sources.put(v, srcSet);
			destinations.put(v, dstSet);

		}

		// clone hashsets inputs and outputs

		for (Vertex ov : other.inputs)
			inputs.add(corr.get(ov));

		for (Vertex ov : other.outputs)
			outputs.add(corr.get(ov));

		// clone pins

		for (Pair<Pair<Vertex, Vertex>, String> entry : other.pins.entrySet()) {

			Vertex ov1 = entry.first.first;
			Vertex ov2 = entry.first.second;

			Vertex v1 = corr.get(ov1);
			Vertex v2 = corr.get(ov2);

			String s = entry.second;

			pins.put(v1, v2, s);

		}

		// clone modConnections

		for (Pair<Pair<Vertex, String>, Vertex> entry : other.modConnections.entrySet()) {

			Vertex ov1 = entry.first.first;
			Vertex ov2 = entry.second;

			Vertex v1 = corr.get(ov1);
			Vertex v2 = corr.get(ov2);

			String s = entry.first.second;

			modConnections.put(v1, s, v2);

		}

		return corr;

	}

	public void addInput(Vertex v) {

		inputs.add(v);

	}

	public void addOutput(Vertex v) {

		outputs.add(v);

	}

	public boolean isInput(Vertex net) {

		return inputs.contains(net);

	}

	public boolean isOutput(Vertex net) {

		return outputs.contains(net);

	}

}
