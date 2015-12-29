package edu.columbia.cs.psl.ioclones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import edu.columbia.cs.psl.ioclones.analysis.DependentValue;
import edu.columbia.cs.psl.ioclones.analysis.DependentValueInterpreter;

public class DependencyAnalyzer extends MethodVisitor {
	
	private static final Logger logger = LogManager.getLogger(DependencyAnalyzer.class);
	
	public static final String OUTPUT_MSG = "__$$COLUMBIA_IO_OUTPUT";
	
	public static final String INPUT_MSG = "__$$COLUMBIA_IO_INPUT";
	
	public DependencyAnalyzer(final String className, 
			int access, 
			final String name, 
			final String desc, 
			String signature, 
			String[] exceptions, 
			final MethodVisitor cmv, 
			boolean debug) {
		
		super(Opcodes.ASM5, 
				new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions) {
			
			@Override
			public void visitEnd() {
				System.out.println("Analyzing " + className + " " + name + " " + desc);
				DependentValueInterpreter dvi = new DependentValueInterpreter();
				Analyzer a = new Analyzer(dvi);
				try {
					Frame[] fr = a.analyze(className, this);
															
					//1st round, collect vals relevant to outputs
					//LinkedList<DependentValue> inputs = new LinkedList<DependentValue>();
					Map<DependentValue, LinkedList<DependentValue>> ios = 
							new HashMap<DependentValue, LinkedList<DependentValue>>();
					AbstractInsnNode insn = this.instructions.getFirst();
					int i = 0;
					while(insn != null) {
						Frame fn = fr[i];
						if(fn != null) {							
							//does this insn create output?
							switch(insn.getOpcode()) {
								//The outputs are values exit after the method ends
								//Include return value and values written to input objs
								case Opcodes.IRETURN:
								case Opcodes.LRETURN:
								case Opcodes.FRETURN:
								case Opcodes.DRETURN:
								case Opcodes.ARETURN:
								case Opcodes.PUTSTATIC:
									//What are we returning?
									DependentValue retVal = (DependentValue)fn.getStack(fn.getStackSize() - 1);
									LinkedList<DependentValue> toOutput = retVal.tag();
									retVal.addOutSink(insn);
									
									//The first will be the ret itself
									toOutput.removeFirst();
									ios.put(retVal, toOutput);
									//inputs.addAll(toOutput);
									System.out.println("Output val with inst: " + retVal + " " + insn);
									System.out.println("Dependent val: " + toOutput);
									/*List<List<AbstractInsnNode>> srcs = new ArrayList<List<AbstractInsnNode>>();
									toOutput.forEach(v->{
										srcs.add(v.getSrcs());
									});
									System.out.println("Dep srcs: " + srcs);*/
									break;									
							}
						}
						i++;
						insn = insn.getNext();
					}
					
					if (debug) {
						this.debug(fr);
					}
					
					Map<Integer, DependentValue> params = dvi.getParams();
					System.out.println("Input param: " + params);
					params.forEach((id, val)->{
						if (val.getDeps() != null && val.getDeps().size() > 0) {
							//This means that the input is an object that has been written
							System.out.println("Dirty input val: " + val);
							
							val.getDeps().forEach(d->{
								System.out.println("Written to input (output): " + d + " " + d.getInSrcs());
								LinkedList<DependentValue> toOutput = d.tag();
								System.out.println("Check to output: " + toOutput);
								
								if (toOutput.size() > 0) {
									//The first will be d itself
									toOutput.removeFirst();
									//inputs.addAll(toOutput);
									ios.put(d, toOutput);
									System.out.println("Dependent val: " + toOutput);
								} else {
									logger.info("Visited value: " + d);
								}
								
								
								/*if (d.getSrcs() != null && d.getSrcs().size() > 0) {
									d.getSrcs().forEach(src-> {
										this.instructions.insertBefore(src, new LdcInsnNode(OUTPUT_MSG));
									});
								}*/
							});
						}
					});
										
					System.out.println("Output number: " + ios.size());
					for (DependentValue o: ios.keySet()) {
						System.out.println("Output: " + o);
						LinkedList<DependentValue> inputs = ios.get(o);
						
						//If o's out sinks are null, something wrong
						if (o.getOutSinks() != null) {
							o.getOutSinks().forEach(sink->{
								this.instructions.insertBefore(sink, new LdcInsnNode(OUTPUT_MSG));
							});
							
							if (inputs != null) {
								for (DependentValue input: inputs) {
									if (input.getInSrcs() == null 
											|| input.getInSrcs().size() == 0) {
										continue ;
									}
									
									input.getInSrcs().forEach(src->{
										this.instructions.insertBefore(src, new LdcInsnNode(INPUT_MSG));
									});
								}
							}
							
							//In case the input and output are the same value
							if (o.getInSrcs() != null) {
								//System.out.println("I is O: " + o);
								o.getInSrcs().forEach(src->{
									this.instructions.insertBefore(src, new LdcInsnNode(INPUT_MSG));
								});
							}
						} else {
							logger.error("Invalid summarziation of output insts: " + o);
						}
					}
					
					/*for(DependentValue v : inputs) {
						System.out.println("Input: " + v);
						if (v.getSrcs() != null) {
							v.getSrcs().forEach(src->{
								System.out.println("Input instruction: " + src);
								this.instructions.insert(src, new LdcInsnNode("Input val: " + v));
							});
							
						}
					}*/
				} catch (AnalyzerException e) {
					e.printStackTrace();
				}
				super.visitEnd();
				this.accept(cmv);
				System.out.println();
			}
			
			public void debug(Frame[] fr) {
				//print debug info
				AbstractInsnNode insn = this.instructions.getFirst();
				int i = 0;
				while(insn != null) {
					Frame fn = fr[i];
					if(fn != null) {
						String stack = "Stack: ";
						for(int j = 0; j < fn.getStackSize(); j++) {
							stack += fn.getStack(j)+ " ";
						}
							
						String locals = "Locals ";
						for(int j = 0; j < fn.getLocals(); j++) {
							locals += fn.getLocal(j) + " ";
						}

						this.instructions.insertBefore(insn, new LdcInsnNode(stack));
						this.instructions.insertBefore(insn, new LdcInsnNode(locals));
					}
					i++;
					insn = insn.getNext();
				}
			}
		});
	}

}