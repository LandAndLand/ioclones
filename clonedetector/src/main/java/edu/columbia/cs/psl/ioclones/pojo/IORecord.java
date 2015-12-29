package edu.columbia.cs.psl.ioclones.pojo;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;

import edu.columbia.cs.psl.ioclones.utils.GlobalInfoRecorder;
import edu.columbia.cs.psl.ioclones.utils.IOUtils;

public class IORecord {
	
	private String methodKey;
	
	private int id;
	
	private Set<Integer> stopVar = new HashSet<Integer>();
	
	private List<Object> inputs = new ArrayList<Object>();
	
	private List<Object> outputs = new ArrayList<Object>();
	
	public IORecord(String methodKey) {
		this.methodKey = methodKey;
		this.id = GlobalInfoRecorder.getMethodIndex();
		//System.out.println("Instantiate io record: " + this.methodKey + " " + this.id);
	}
	
	public String getMethodKey() {
		return this.methodKey;
	}
	
	public int getId() {
		return this.id;
	}
	
	public void registerInput(Object i, boolean ser, int varId) {
		if (this.stopVar.contains(varId)) {
			return ;
		}
		
		if (i == null) {
			ser = false;
		}
		
		Object insert = null;
		if (ser) {
			insert = IOUtils.newObject(i);
		} else {
			insert = i;
		}
		
		System.out.println("Register in: " + insert);
		this.inputs.add(insert);
	}
	
	public void registerInput(Object i, boolean ser) {
		this.registerInput(i, ser, -1);
	}
	
	public void stopRegisterInput(int varId) {
		this.stopVar.add(varId);
	}
	
	public void registerOutput(Object o, boolean ser) {
		if (o == null) {
			ser = false;
		}
		
		Object insert = null;
		if (ser) {
			System.out.println("Check o: " + methodKey + " " + o);
			insert = IOUtils.newObject(o);
		} else {
			insert = o;
		}
		
		System.out.println("Register output: " + insert);
		this.outputs.add(insert);
	}
			
	public List<Object> getInputs() {
		return this.inputs;
	}
	
	public List<Object> getOutputs() {
		return this.outputs;
	}
		
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Method: " + this.methodKey + "\n");
		sb.append("ID: " + this.id + "\n");
		sb.append("Inputs: " + this.inputs + "\n");
		sb.append("Outputs: " + this.outputs + "\n");
		return sb.toString();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof IORecord)) {
			return false;
		}
		
		IORecord tmp = (IORecord)obj;
		if (!tmp.getMethodKey().equals(this.methodKey)) {
			return false;
		}
		
		String tmpInput = IOUtils.fromObj2XML(tmp.getInputs());
		String thisInput = IOUtils.fromObj2XML(this.inputs);
		if (!tmpInput.equals(thisInput)) {
			return false;
		}
		
		String tmpOutput = IOUtils.fromObj2XML(tmp.getOutputs());
		String thisOutput = IOUtils.fromObj2XML(this.outputs);
		if (!tmpOutput.equals(thisOutput)) {
			return false;
		}
		
		return true;
	}
	
	@Override
	public int hashCode() {
		int result = 17;
		String inputString = IOUtils.fromObj2XML(this.inputs);
		String outputString = IOUtils.fromObj2XML(this.outputs);
		result = 31 * this.methodKey.hashCode();
		result = 31 * result + inputString.hashCode();
		result = 31 * result + outputString.hashCode();
		return result;
	}	
}
