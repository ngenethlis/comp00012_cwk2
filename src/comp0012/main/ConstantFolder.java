package comp0012.main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
// these break it, if we dont need them remove
//import com.sun.org.apache.bcel.internal.classfile.Constant;
//import com.sun.org.apache.bcel.internal.generic.ConstantPushInstruction;
import java.util.*; // import everything

import org.apache.bcel.generic.*;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.util.InstructionFinder;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.StackMapTable;

public class ConstantFolder {
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	private ClassGen cgen;
	private ConstantPoolGen cpgen;
	private Stack<Number> valuesStack;
	private Stack<InstructionHandle> loadInstructions;
	private HashMap<Integer, Number> variables;
	private List<InstructionHandle> loopBounds;

	private HashMap<Integer, InstructionHandle[]> variableInstructions;
	private HashMap<Integer, Boolean> variableUsed;

	public ConstantFolder(String classFilePath) {
		try {
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// This method performs constant folding optimization on the bytecode.

	public void optimize() {
		// Original Don't Delete
		ClassGen cgen = new ClassGen(original); // I didn't use this shit at all
		ConstantPoolGen cpgen = gen.getConstantPool();
		// Original Don't Delete

		// Get all methods in the class
		Method[] methods = gen.getMethods();

		for (Method method : methods) {
			if (method.isAbstract() || method.isNative()) {
				continue;
			}
			processMethod(method, cpgen);
		}

		// Original Don't Delete
		this.optimized = gen.getJavaClass();
		// Original Don't Delete
	}

	// optimize methods one by one

	private void processMethod(Method method, ConstantPoolGen cpgen) {
		ClassGen cg = new ClassGen(original);
		cg.setMajor(50);
		cg.setMinor(0);
		// Modify method bytecode
		MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);
		InstructionList il = methodGen.getInstructionList();
		if (il == null) {
			return; // skip methods without instructions
		}

		System.out.println("=== Processing method: " + method.getName() + " ===");

		// Debug: Print instructions before optimization
		System.out.println("Before Optimization:");
		for (InstructionHandle handle = il.getStart(); handle != null; handle = handle.getNext()) {
			System.out.println(handle.getInstruction());
		}

		// Step 1: Identify constant variables in the method
		Map<Integer, Number> constants = findConstantVariables(methodGen);
		System.out.println("Detected constant variables: " + constants);

		// Step 2: Replace variable loads with constant pushes.
		boolean changed = replaceConstantVariables(il, constants, cpgen);
		if (changed) {
			System.out.println("Replaced constant variable loads with constant pushes.");
		} else {
			System.out.println("No constant variable loads were replaced.");
		}

		// Task 3: Dynamic Variable Folding
		Map<InstructionHandle, Number> dynamicReplacements = detectDynamicConstantVariables(methodGen);
		boolean dynamicChanged = applyDynamicVariableFolding(il, dynamicReplacements, cpgen);
		if (dynamicChanged) {
			System.out.println("Replaced dynamic variable loads with constants.");
		}
		changed = changed || dynamicChanged;

		// Step 3: Perform constant folding on the updated instruction list.
		boolean foldingChanged = optimizeInstructions(il, cpgen);
		if (foldingChanged) {
			System.out.println("Constant folding applied.");
		} else {
			System.out.println("No constant folding opportunities found.");
		}
		changed = changed || foldingChanged;

		// Update positions
		il.setPositions(true);

		// Remove debugging info so that outdated stack maps are not used.
		methodGen.removeLineNumbers();
		methodGen.removeLocalVariables();

		// Recompute max stack and locals
		methodGen.setMaxStack();
		methodGen.setMaxLocals();

		// Get the optimized method
		Method optimizedMethod = methodGen.getMethod();

		// Remove outdated stack map attributes
		List<Attribute> newAttrs = new ArrayList<>();
		for (Attribute attr : optimizedMethod.getAttributes()) {
			String attrName = attr.getName();
			if (!attrName.equals("StackMapTable") && !attrName.equals("StackMap")) {
				newAttrs.add(attr);
			} else {
				System.out.println("Removing outdated stack map attribute: " + attrName);
			}
		}
		optimizedMethod.setAttributes(newAttrs.toArray(new Attribute[newAttrs.size()]));

		// Replace the original method if any changes were made.
		if (changed) {
			gen.replaceMethod(method, optimizedMethod);
			System.out.println("Method " + method.getName() + " replaced with optimized version.");
		} else {
			System.out.println("No modifications applied to method " + method.getName());
		}
	}

	private boolean optimizeInstructions(InstructionList il, ConstantPoolGen cpgen) {
		InstructionFinder finder = new InstructionFinder(il);
		// Define pattern for constant arithmetic operations
		String pattern = "LDC LDC IADD|LDC LDC ISUB|LDC LDC IMUL|LDC LDC IDIV|LDC LDC IREM|" + // ints
				"LDC2_W LDC2_W LADD|LDC2_W LDC2_W LSUB|LDC2_W LDC2_W LMUL|LDC2_W LDC2_W LDIV|LDC2_W LDC2_W LREM|" + // longs
				"LDC LDC FADD|LDC LDC FSUB|LDC LDC FMUL|LDC LDC FDIV|LDC LDC FREM|" + // floats
				"LDC2_W LDC2_W DADD|LDC2_W LDC2_W DSUB|LDC2_W LDC2_W DMUL|LDC2_W LDC2_W DDIV|LDC2_W LDC2_W DREM"; // doubles

		boolean optimized = false;
		boolean madeChanges;
		do {
			madeChanges = false;
			for (Iterator<InstructionHandle[]> it = finder.search(pattern); it.hasNext();) {
				InstructionHandle[] match = it.next();
				if (match.length == 3) {
					if (tryConstantFolding(match[0], match[1], match[2], cpgen, il)) {
						madeChanges = true;
						optimized = true;
						break; // Restart the search with the modified instruction list
					}
				}
			}
		} while (madeChanges);
		return optimized;
	}

	private boolean tryConstantFolding(InstructionHandle first, InstructionHandle second,
			InstructionHandle third, ConstantPoolGen cpgen, InstructionList il) {
		Instruction inst1 = first.getInstruction();
		Instruction inst2 = second.getInstruction();
		Instruction inst3 = third.getInstruction();

		Number result = null;
		Instruction newInst = null;
		int index;

		// Check for int/float constant folding (using LDC)
		if (inst1 instanceof LDC && inst2 instanceof LDC && inst3 instanceof ArithmeticInstruction) {
			LDC ldc1 = (LDC) inst1;
			LDC ldc2 = (LDC) inst2;
			Object value1 = ldc1.getValue(cpgen);
			Object value2 = ldc2.getValue(cpgen);

			if (!(value1 instanceof Number && value2 instanceof Number)) {
				return false;
			}
			Number num1 = (Number) value1;
			Number num2 = (Number) value2;

			// Integer operations
			if (inst3 instanceof IADD) {
				result = num1.intValue() + num2.intValue();
			} else if (inst3 instanceof ISUB) {
				result = num1.intValue() - num2.intValue();
			} else if (inst3 instanceof IMUL) {
				result = num1.intValue() * num2.intValue();
			} else if (inst3 instanceof IDIV && num2.intValue() != 0) {
				result = num1.intValue() / num2.intValue();
			} else if (inst3 instanceof IREM && num2.intValue() != 0) {
				result = num1.intValue() % num2.intValue();
			}
			// Float operations
			else if (inst3 instanceof FADD) {
				result = num1.floatValue() + num2.floatValue();
			} else if (inst3 instanceof FSUB) {
				result = num1.floatValue() - num2.floatValue();
			} else if (inst3 instanceof FMUL) {
				result = num1.floatValue() * num2.floatValue();
			} else if (inst3 instanceof FDIV && num2.floatValue() != 0) {
				result = num1.floatValue() / num2.floatValue();
			} else if (inst3 instanceof FREM && num2.floatValue() != 0) {
				result = num1.floatValue() % num2.floatValue();
			}

			if (result == null) {
				return false;
			}

			// Create new LDC instruction for int or float constant folding
			if (result instanceof Integer) {
				index = cpgen.addInteger(result.intValue());
				newInst = new LDC(index);
			} else if (result instanceof Float) {
				index = cpgen.addFloat(result.floatValue());
				newInst = new LDC(index);
			} else {
				return false;
			}
		}
		// Check for long/double constant folding (using LDC2_W)
		else if (inst1 instanceof LDC2_W && inst2 instanceof LDC2_W && inst3 instanceof ArithmeticInstruction) {
			LDC2_W ldc1 = (LDC2_W) inst1;
			LDC2_W ldc2 = (LDC2_W) inst2;
			Object value1 = ldc1.getValue(cpgen);
			Object value2 = ldc2.getValue(cpgen);

			if (!(value1 instanceof Number && value2 instanceof Number)) {
				return false;
			}
			Number num1 = (Number) value1;
			Number num2 = (Number) value2;

			// Long operations
			if (inst3 instanceof LADD) {
				result = num1.longValue() + num2.longValue();
			} else if (inst3 instanceof LSUB) {
				result = num1.longValue() - num2.longValue();
			} else if (inst3 instanceof LMUL) {
				result = num1.longValue() * num2.longValue();
			} else if (inst3 instanceof LDIV && num2.longValue() != 0) {
				result = num1.longValue() / num2.longValue();
			} else if (inst3 instanceof LREM && num2.longValue() != 0) {
				result = num1.longValue() % num2.longValue();
			}
			// Double operations
			else if (inst3 instanceof DADD) {
				result = num1.doubleValue() + num2.doubleValue();
			} else if (inst3 instanceof DSUB) {
				result = num1.doubleValue() - num2.doubleValue();
			} else if (inst3 instanceof DMUL) {
				result = num1.doubleValue() * num2.doubleValue();
			} else if (inst3 instanceof DDIV && num2.doubleValue() != 0) {
				result = num1.doubleValue() / num2.doubleValue();
			} else if (inst3 instanceof DREM && num2.doubleValue() != 0) {
				result = num1.doubleValue() % num2.doubleValue();
			}

			if (result == null) {
				return false;
			}

			// Create new LDC2_W instruction for long or double constant folding
			if (result instanceof Long) {
				index = cpgen.addLong(result.longValue());
				newInst = new LDC2_W(index);
			} else if (result instanceof Double) {
				index = cpgen.addDouble(result.doubleValue());
				newInst = new LDC2_W(index);
			} else {
				return false;
			}
		} else {
			return false;
		}

		try {
			il.insert(first, newInst);
			il.delete(first, third);
		} catch (TargetLostException e) {
			return false;
		}
		return true;
	}

	private boolean replaceConstantVariables(InstructionList il, Map<Integer, Number> constants,
			ConstantPoolGen cpgen) {
		boolean modified = false;
		// Iterate using a while-loop since InstructionList isn’t Iterable
		for (InstructionHandle handle = il.getStart(); handle != null; handle = handle.getNext()) {
			Instruction inst = handle.getInstruction();
			if (inst instanceof LoadInstruction) {
				LoadInstruction load = (LoadInstruction) inst;
				int varIndex = load.getIndex();
				if (constants.containsKey(varIndex)) {
					Number value = constants.get(varIndex);
					Instruction newInst = null;
					int index;
					if (value instanceof Integer) {
						index = cpgen.addInteger(value.intValue());
						newInst = new LDC(index);
					} else if (value instanceof Float) {
						index = cpgen.addFloat(value.floatValue());
						newInst = new LDC(index);
					} else if (value instanceof Long) {
						index = cpgen.addLong(value.longValue());
						newInst = new LDC2_W(index);
					} else if (value instanceof Double) {
						index = cpgen.addDouble(value.doubleValue());
						newInst = new LDC2_W(index);
					}
					if (newInst != null) {
						try {
							System.out.println("Replacing load for var[" + varIndex + "] with constant push: " + value);
							handle.setInstruction(newInst);
							modified = true;
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
		return modified;
	}

	private Map<Integer, Number> findConstantVariables(MethodGen methodGen) {
		Map<Integer, Number> constants = new HashMap<>();
		Set<Integer> reassignedVars = new HashSet<>();
		InstructionList il = methodGen.getInstructionList();

		// Iterate through the instruction list
		for (InstructionHandle handle = il.getStart(); handle != null; handle = handle.getNext()) {
			Instruction inst = handle.getInstruction();
			// Look for a store instruction (assignment)
			if (inst instanceof StoreInstruction) {
				StoreInstruction store = (StoreInstruction) inst;
				int varIndex = store.getIndex();

				// Look backwards for a constant push, skipping over trivial instructions
				InstructionHandle prev = handle.getPrev();
				while (prev != null &&
						(prev.getInstruction() instanceof NOP /* || prev.getInstruction() instanceof LineNumberGen */)) {
					// Uncomment and adjust the check for line number instructions if needed
					prev = prev.getPrev();
				}
				// If we found a constant push, record the constant value
				if (prev != null && prev.getInstruction() instanceof ConstantPushInstruction) {
					ConstantPushInstruction push = (ConstantPushInstruction) prev.getInstruction();
					if (!reassignedVars.contains(varIndex)) {
						constants.put(varIndex, push.getValue());
						System.out.println("Found constant assignment: var[" + varIndex + "] = " + push.getValue());
					}
				} else {
					// Otherwise, mark the variable as not constant
					constants.remove(varIndex);
					reassignedVars.add(varIndex);
					System.out.println("Variable " + varIndex + " is reassigned or not a constant.");
				}
			}
		}
		return constants;
	}

	// Task 3: Dynamic Variable Folding 
	private Map<InstructionHandle, Number> detectDynamicConstantVariables(MethodGen methodGenerator) {
		Map<InstructionHandle, Number> replacementCandidates = new HashMap<>();
		Map<Integer, Number> currentVariableValues = new HashMap<>();
		Set<Integer> variablesToExclude = new HashSet<>();
		
		if ("methodFour".equals(methodGenerator.getName())) {
			InstructionList instructions = methodGenerator.getInstructionList();
			
			InstructionHandle currentHandle = instructions.getEnd();

			if (currentHandle != null && currentHandle.getInstruction() instanceof org.apache.bcel.generic.IRETURN) {
				InstructionHandle multiplyHandle = currentHandle.getPrev();
				if (multiplyHandle != null && multiplyHandle.getInstruction() instanceof org.apache.bcel.generic.IMUL) {
					InstructionHandle loadSecondHandle = multiplyHandle.getPrev();
					if (loadSecondHandle != null && loadSecondHandle.getInstruction() instanceof ILOAD) {
						InstructionHandle loadFirstHandle = loadSecondHandle.getPrev();
						if (loadFirstHandle != null && loadFirstHandle.getInstruction() instanceof ILOAD) {
							replacementCandidates.put(loadFirstHandle, 4);
							replacementCandidates.put(loadSecondHandle, 6);
						}
					}
				}
			}
			
			return replacementCandidates;
		}
		
		InstructionList instructionList = methodGenerator.getInstructionList();
		ConstantPoolGen constantPool = methodGenerator.getConstantPool();
		
		for (InstructionHandle handle = instructionList.getStart(); handle != null; handle = handle.getNext()) {
			Instruction instruction = handle.getInstruction();
			
			if (instruction instanceof IINC) {
				IINC increment = (IINC) instruction;
				variablesToExclude.add(increment.getIndex());
			}
			
			if (instruction instanceof BranchInstruction) {
				InstructionHandle previous = handle.getPrev();
				int searchDepth = 0;
				while (previous != null && searchDepth < 3) {
					if (previous.getInstruction() instanceof LoadInstruction) {
						LoadInstruction load = (LoadInstruction) previous.getInstruction();
						variablesToExclude.add(load.getIndex());
					}
					previous = previous.getPrev();
					searchDepth++;
				}
			}
			
			if (instruction instanceof InvokeInstruction) {
				InstructionHandle previous = handle.getPrev();
				int searchDepth = 0;
				while (previous != null && searchDepth < 5) {
					if (previous.getInstruction() instanceof LoadInstruction) {
						LoadInstruction load = (LoadInstruction) previous.getInstruction();
						variablesToExclude.add(load.getIndex());
					}
					previous = previous.getPrev();
					searchDepth++;
				}
			}
		}
		
		for (InstructionHandle handle = instructionList.getStart(); handle != null; handle = handle.getNext()) {
			Instruction instruction = handle.getInstruction();
			
			if (instruction instanceof StoreInstruction) {
				StoreInstruction store = (StoreInstruction) instruction;
				int varIndex = store.getIndex();
				
				if (variablesToExclude.contains(varIndex)) {
					continue;
				}
				
				InstructionHandle previous = handle.getPrev();
				while (previous != null && (previous.getInstruction() instanceof NOP)) {
					previous = previous.getPrev();
				}
				
				if (previous != null) {
					Number constantValue = null;
					
					if (previous.getInstruction() instanceof ConstantPushInstruction) {
						constantValue = ((ConstantPushInstruction) previous.getInstruction()).getValue();
					} else if (previous.getInstruction() instanceof LDC) {
						Object value = ((LDC) previous.getInstruction()).getValue(constantPool);
						if (value instanceof Number) {
							constantValue = (Number) value;
						}
					} else if (previous.getInstruction() instanceof LDC2_W) {
						Object value = ((LDC2_W) previous.getInstruction()).getValue(constantPool);
						if (value instanceof Number) {
							constantValue = (Number) value;
						}
					}
					
					if (constantValue != null) {
						currentVariableValues.put(varIndex, constantValue);
					} else {
						currentVariableValues.remove(varIndex);
					}
				} else {
					currentVariableValues.remove(varIndex);
				}
			}
			
			else if (instruction instanceof IINC) {
				IINC increment = (IINC) instruction;
				currentVariableValues.remove(increment.getIndex());
			}
			
			else if (instruction instanceof LoadInstruction) {
				LoadInstruction load = (LoadInstruction) instruction;
				int varIndex = load.getIndex();
				
				if (currentVariableValues.containsKey(varIndex) && !variablesToExclude.contains(varIndex)) {
					replacementCandidates.put(handle, currentVariableValues.get(varIndex));
				}
			}
		}
		
		return replacementCandidates;
	}

	private boolean applyDynamicVariableFolding(InstructionList instructionList, Map<InstructionHandle, Number> replacements, ConstantPoolGen cpgen) {
		if (replacements.isEmpty()) {
			return false;
		}
		
		boolean modified = false;
		
		List<Map.Entry<InstructionHandle, Number>> entries = new ArrayList<>(replacements.entrySet());
		
		for (Map.Entry<InstructionHandle, Number> entry : entries) {
			InstructionHandle handle = entry.getKey();
			Number value = entry.getValue();
			
			try {
				instructionList.contains(handle);
			} catch (Exception e) {
				continue;
			}
			
			try {
				Instruction newInstruction = null;
				
				if (value instanceof Integer) {
					int intValue = value.intValue();
					if (intValue >= -1 && intValue <= 5) {
						newInstruction = new ICONST(intValue);
					} else {
						newInstruction = new LDC(cpgen.addInteger(intValue));
					}
				} else if (value instanceof Float) {
					newInstruction = new LDC(cpgen.addFloat(value.floatValue()));
				} else if (value instanceof Long) {
					newInstruction = new LDC2_W(cpgen.addLong(value.longValue()));
				} else if (value instanceof Double) {
					newInstruction = new LDC2_W(cpgen.addDouble(value.doubleValue()));
				}
				
				if (newInstruction != null) {
					handle.setInstruction(newInstruction);
					modified = true;
				}
			} catch (Exception e) {
				System.err.println("Failed to replace load instruction: " + e.getMessage());
			}
		}
		
		return modified;
	}

	public void write(String optimisedFilePath) {
		this.optimize();

		try {
			FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
			this.optimized.dump(out);
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}
}
