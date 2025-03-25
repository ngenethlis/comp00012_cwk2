package comp0012.main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*; // import everything

import org.apache.bcel.generic.*;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.ConstantPushInstruction;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.LDC2_W;
import org.apache.bcel.generic.StoreInstruction;
import org.apache.bcel.generic.LoadInstruction;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.ArithmeticInstruction;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.DADD;
import org.apache.bcel.generic.DDIV;
import org.apache.bcel.generic.DMUL;
import org.apache.bcel.generic.DREM;
import org.apache.bcel.generic.DSUB;
import org.apache.bcel.generic.FADD;
import org.apache.bcel.generic.FDIV;
import org.apache.bcel.generic.FMUL;
import org.apache.bcel.generic.FREM;
import org.apache.bcel.generic.FSUB;
import org.apache.bcel.generic.IADD;
import org.apache.bcel.generic.IDIV;
import org.apache.bcel.generic.IMUL;
import org.apache.bcel.generic.IREM;
import org.apache.bcel.generic.ISUB;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionTargeter;
import org.apache.bcel.generic.LADD;
import org.apache.bcel.generic.LDIV;
import org.apache.bcel.generic.LMUL;
import org.apache.bcel.generic.LREM;
import org.apache.bcel.generic.LSUB;
import org.apache.bcel.util.InstructionFinder;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.StackMapTable;
import java.util.ArrayList;
import java.util.List;
// these break it, if we dont need them remove
//import com.sun.org.apache.bcel.internal.classfile.Constant;
//import com.sun.org.apache.bcel.internal.generic.ConstantPushInstruction;

import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.TargetLostException;

import java.util.HashMap;
import java.util.Stack;

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
