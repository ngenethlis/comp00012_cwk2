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
import org.apache.bcel.classfile.LocalVariableTable;
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
			// get around stackmap errors
			this.gen.setMajor(50);
			this.gen.setMinor(0);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// This method performs constant folding optimization on the bytecode.
	public void optimize() {
		// Original Don't Delete
		ClassGen cgen = new ClassGen(original);
		ConstantPoolGen cpgen = gen.getConstantPool();

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
	}

	// optimize methods one by one

	private void processMethod(Method method, ConstantPoolGen cpgen) {
		ClassGen cg = new ClassGen(original);
		// get around stack map errors
		cg.setMajor(50);
		cg.setMinor(0);

		MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);
		InstructionList il = methodGen.getInstructionList();
		if (il == null) {
			return; // Skip methods without instructions.
		}

		System.out.println("=== Processing method: " + method.getName() + " from class " + gen.getClassName() + "===");
		// Iteratively optimize until no more changes occur.
		boolean madeChanges;
		int iteration = 0;
		do {
			iteration++;
			System.out.println("Iteration " + iteration + " of optimizations.");

			boolean constFoldChanged = optimizeInstructions(il, cpgen);
			boolean varFoldChanged = foldConstantVariables(methodGen) || foldDynamicVariables(methodGen);
			madeChanges = varFoldChanged || constFoldChanged;

			if (madeChanges) {
				il.setPositions(true); // Update the positions after changes.
			}

		} while (madeChanges);

		// Remove debugging info so outdated stack maps are not used.
		methodGen.removeLineNumbers();
		methodGen.removeLocalVariables();

		// Recompute max stack and locals.
		methodGen.setMaxStack();
		methodGen.setMaxLocals();

		Method optimizedMethod = methodGen.getMethod();

		// Remove outdated stack map attributes.
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
		if (iteration > 1) { // If more than one iteration occurred assume modifications were made.
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

	public boolean foldConstantVariables(MethodGen mg) {
		InstructionList il = mg.getInstructionList();
		ConstantPoolGen cpgen = mg.getConstantPool();
		if (il == null)
			return false;

		boolean changed = false;
		InstructionHandle[] handles = il.getInstructionHandles();
		Map<Integer, ConstantInfo> constantAssignments = new HashMap<>();
		Set<Integer> reassignedVariables = new HashSet<>();

		identifyConstantAssignments(handles, cpgen, constantAssignments, reassignedVariables);
		removeReassignedVariables(constantAssignments, reassignedVariables);

		if (replaceLoadsWithConstants(handles, cpgen, constantAssignments)) {
			changed = true;
		}

		if (removeUnusedAssignments(il, constantAssignments)) {
			changed = true;
		}
		return changed;
	}

	public boolean foldDynamicVariables(MethodGen mg) {
		InstructionList il = mg.getInstructionList();
		ConstantPoolGen cpgen = mg.getConstantPool();

		if (il == null) {
			return false;
		}

		boolean changed = false;
		InstructionHandle[] handles = il.getInstructionHandles();
		// Maps a local variable index to its current constant value.
		Map<Integer, ConstantInfo> variableValues = new HashMap<>();

		// Process instructions one by one.
		for (int i = 0; i < handles.length; i++) {
			InstructionHandle current = handles[i];
			Instruction inst = current.getInstruction();

			if (inst instanceof StoreInstruction) {
				// For a store, check the previous instruction for a constant push.
				int varIndex = ((StoreInstruction) inst).getIndex();
				if (i > 0) {
					Instruction prevInst = handles[i - 1].getInstruction();
					ConstantInfo info = extractConstantInfo(prevInst, cpgen);
					if (info != null) {
						variableValues.put(varIndex, info);
					} else {
						variableValues.remove(varIndex);
					}
				} else {
					// No preceding instruction -> clear any constant mapping.
					variableValues.remove(varIndex);
				}
			} else if (inst instanceof LoadInstruction && !current.hasTargeters()) {
				// For a load, if a constant is active for the variable, replace it.
				int varIndex = ((LoadInstruction) inst).getIndex();
				if (variableValues.containsKey(varIndex)) {
					Instruction replacement = createConstantLoad(variableValues.get(varIndex), cpgen);
					if (replacement != null) {
						current.setInstruction(replacement);
						changed = true;
					}
				}
			}
		}

		return changed;
	}

	// ================== Helper Methods ==================

	// === Constant specific====
	//
	private void identifyConstantAssignments(InstructionHandle[] handles, ConstantPoolGen cpgen,
			Map<Integer, ConstantInfo> assignments, Set<Integer> reassigns) {
		for (int i = 0; i < handles.length - 1; i++) {
			Instruction inst = handles[i].getInstruction();
			Instruction next = handles[i + 1].getInstruction();

			if (!(next instanceof StoreInstruction))
				continue;
			int index = ((StoreInstruction) next).getIndex();

			if (assignments.containsKey(index)) {
				reassigns.add(index);
				continue;
			}

			ConstantInfo constInfo = extractConstantInfo(inst, cpgen);
			if (constInfo != null) {
				assignments.put(index, constInfo);
			}
		}
	}

	private void removeReassignedVariables(Map<Integer, ConstantInfo> assignments, Set<Integer> reassigns) {
		for (int index : reassigns) {
			assignments.remove(index);
		}
	}

	private boolean replaceLoadsWithConstants(InstructionHandle[] handles, ConstantPoolGen cpgen,
			Map<Integer, ConstantInfo> assignments) {
		boolean changed = false;

		for (InstructionHandle handle : handles) {
			Instruction inst = handle.getInstruction();
			if (handle.hasTargeters() || !(inst instanceof LoadInstruction))
				continue;

			int index = ((LoadInstruction) inst).getIndex();
			if (!assignments.containsKey(index))
				continue;

			Instruction replacement = createConstantLoad(assignments.get(index), cpgen);
			if (replacement != null) {
				handle.setInstruction(replacement);
				changed = true;
			}
		}

		return changed;
	}

	private boolean removeUnusedAssignments(InstructionList il, Map<Integer, ConstantInfo> assignments) {
		boolean changed = false;

		for (int index : new HashSet<>(assignments.keySet())) {
			if (isVariableUsed(il, index))
				continue;

			InstructionHandle h = il.getStart();
			while (h != null && h.getNext() != null) {
				Instruction inst = h.getInstruction();
				Instruction nextInst = h.getNext().getInstruction();

				if (nextInst instanceof StoreInstruction && ((StoreInstruction) nextInst).getIndex() == index) {
					InstructionHandle next = h.getNext().getNext();
					try {
						il.delete(h, h.getNext());
						changed = true;
					} catch (TargetLostException e) {
						for (InstructionHandle target : e.getTargets()) {
							for (InstructionTargeter t : target.getTargeters()) {
								t.updateTarget(target, next);
							}
						}
					}
					break;
				}
				h = h.getNext();
			}
		}

		return changed;
	}

	private boolean isVariableUsed(InstructionList il, int index) {
		for (InstructionHandle h = il.getStart(); h != null; h = h.getNext()) {
			Instruction inst = h.getInstruction();
			if (inst instanceof LoadInstruction && ((LoadInstruction) inst).getIndex() == index) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Extracts constant information from an instruction if it is a constant push.
	 * This method recognizes:
	 * - LDC
	 * - LDC2_W
	 * - ConstantPushInstruction
	 *
	 * Returns a ConstantInfo or null if the instruction does not push a constant.
	 */
	private ConstantInfo extractConstantInfo(Instruction inst, ConstantPoolGen cpgen) {
		Number value = null;
		Type type = null;

		if (inst instanceof LDC) {
			Object val = ((LDC) inst).getValue(cpgen);
			if (val instanceof Integer) {
				value = (Integer) val;
				type = Type.INT;
			} else if (val instanceof Float) {
				value = (Float) val;
				type = Type.FLOAT;
			}
		} else if (inst instanceof LDC2_W) {
			Object val = ((LDC2_W) inst).getValue(cpgen);
			if (val instanceof Double) {
				value = (Double) val;
				type = Type.DOUBLE;
			} else if (val instanceof Long) {
				value = (Long) val;
				type = Type.LONG;
			}
		} else if (inst instanceof ConstantPushInstruction) {
			value = ((ConstantPushInstruction) inst).getValue();
			if (value instanceof Integer) {
				type = Type.INT;
			} else if (value instanceof Float) {
				type = Type.FLOAT;
			}
		}

		return (value != null && type != null) ? new ConstantInfo(value, type) : null;
	}

	/**
	 * Creates an instruction that loads the constant based on the type
	 * in helper class ConstantInfo.
	 */
	private Instruction createConstantLoad(ConstantInfo constInfo, ConstantPoolGen cpgen) {
		// Using toString() of Type is one way to compare.
		// Alternatively, compare with Type.INT, etc., if your Type class supports it.
		if (constInfo.type.equals(Type.INT)) {
			return new LDC(cpgen.addInteger(constInfo.value.intValue()));
		} else if (constInfo.type.equals(Type.FLOAT)) {
			return new LDC(cpgen.addFloat(constInfo.value.floatValue()));
		} else if (constInfo.type.equals(Type.DOUBLE)) {
			return new LDC2_W(cpgen.addDouble(constInfo.value.doubleValue()));
		} else if (constInfo.type.equals(Type.LONG)) {
			return new LDC2_W(cpgen.addLong(constInfo.value.longValue()));
		}
		return null;
	}

	// == Dynamic Specific helpers ==

	/**
	 * Checks a StoreInstruction at the given index.
	 * if prev instruction pushes const, update mapping
	 * Otherwise remove the variable from the mapping.
	 */
	private void processStoreInstruction(InstructionHandle[] handles, int i, ConstantPoolGen cpgen,
			Map<Integer, ConstantInfo> variableValues) {
		int index = ((StoreInstruction) handles[i].getInstruction()).getIndex();
		if (i > 0) {
			Instruction prevInst = handles[i - 1].getInstruction();
			ConstantInfo info = extractConstantInfo(prevInst, cpgen);
			if (info != null) {
				variableValues.put(index, info);
			} else {
				variableValues.remove(index);
			}
		} else {
			variableValues.remove(index);
		}
	}

	/**
	 * Checks a LoadInstruction.
	 * If const val is active for var replace the load with a const load
	 */
	private boolean processLoadInstruction(InstructionHandle current, ConstantPoolGen cpgen,
			Map<Integer, ConstantInfo> variableValues) {
		int index = ((LoadInstruction) current.getInstruction()).getIndex();
		if (variableValues.containsKey(index)) {
			ConstantInfo info = variableValues.get(index);
			Instruction replacement = createConstantLoad(info, cpgen);
			if (replacement != null) {
				current.setInstruction(replacement);
				return true;
			}
		}
		return false;
	}

	// write optimised file
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

	// ============== Helper Class ================
	// holds constant value and its type.
	class ConstantInfo {
		public Number value;
		public Type type;

		public ConstantInfo(Number value, Type type) {
			this.value = value;
			this.type = type;
		}

		@Override
		public String toString() {
			return "{number: " + value + ", type: " + type + "}";
		}
	}
}
