package comp0012.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.apache.bcel.util.InstructionFinder;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;

public
class ConstantFolder {
private
  ClassGen gen;
private
  JavaClass optimized;

public
  ConstantFolder(String classFilePath) {
    try {
      ClassParser parser = new ClassParser(classFilePath);
      JavaClass original = parser.parse();
      this.gen = new ClassGen(original);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

public void optimize() {
    ConstantPoolGen cpgen = gen.getConstantPool();

    // Step 1: Propagate constant variables before optimizing arithmetic expressions
    propagateConstants();

    // Step 2: Perform constant folding optimizations (your existing logic)
    Method[] methods = gen.getMethods();

    for (int i = 0; i < methods.length; i++) {
        Method method = methods[i];

        if (method.isAbstract() || method.isNative()) {
            continue;
        }

        MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);
        InstructionList instructionList = methodGen.getInstructionList();
        if (instructionList == null) {
            continue;
        }

        InstructionFinder finder = new InstructionFinder(instructionList);
        String pattern = "LDC LDC IADD|LDC LDC ISUB|LDC LDC IMUL|LDC LDC IDIV|LDC LDC IREM|" +
                         "LDC2_W LDC2_W LADD|LDC2_W LDC2_W LSUB|LDC2_W LDC2_W LMUL|LDC2_W LDC2_W LDIV|LDC2_W LDC2_W LREM|" +
                         "LDC LDC FADD|LDC LDC FSUB|LDC LDC FMUL|LDC LDC FDIV|LDC LDC FREM|" +
                         "LDC2_W LDC2_W DADD|LDC2_W LDC2_W DSUB|LDC2_W LDC2_W DMUL|LDC2_W LDC2_W DDIV|LDC2_W LDC2_W DREM";

        boolean optimized = false;
        boolean madeChanges;

        do {
            madeChanges = false;

            for (Iterator<InstructionHandle[]> it = finder.search(pattern); it.hasNext();) {
                InstructionHandle[] match = it.next();

                if (match.length == 3) {
                    InstructionHandle first = match[0];
                    InstructionHandle second = match[1];
                    InstructionHandle third = match[2];

                    Instruction inst1 = first.getInstruction();
                    Instruction inst2 = second.getInstruction();
                    Instruction inst3 = third.getInstruction();

                    if (inst1 instanceof LDC && inst2 instanceof LDC && inst3 instanceof ArithmeticInstruction) {
                        LDC ldc1 = (LDC) inst1;
                        LDC ldc2 = (LDC) inst2;

                        Object value1 = ldc1.getValue(cpgen);
                        Object value2 = ldc2.getValue(cpgen);

                        if (value1 instanceof Number && value2 instanceof Number) {
                            Number num1 = (Number) value1;
                            Number num2 = (Number) value2;
                            Number result = null;

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
                            } else if (inst3 instanceof FADD) {
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

                            if (result != null) {
                                Instruction newInst;
                                int index;

                                if (result instanceof Integer) {
                                    index = cpgen.addInteger(result.intValue());
                                    newInst = new LDC(index);
                                } else if (result instanceof Float) {
                                    index = cpgen.addFloat(result.floatValue());
                                    newInst = new LDC(index);
                                } else {
                                    continue;
                                }

                                try {
                                    instructionList.insert(first, newInst);
                                    instructionList.delete(first, third);
                                    madeChanges = true;
                                    optimized = true;
                                    break;
                                } catch (TargetLostException e) {
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
        } while (madeChanges);

        if (optimized) {
            methodGen.setMaxStack();
            methodGen.setMaxLocals();
            gen.replaceMethod(method, methodGen.getMethod());
        }
    }

    this.optimized = gen.getJavaClass();
}

private Number
computeConstant(Instruction inst1, Instruction inst2, Instruction inst3,
                ConstantPoolGen cpgen) {
  Object value1 = getLDCValue(inst1, cpgen);
  Object value2 = getLDCValue(inst2, cpgen);
  if (!(value1 instanceof Number) || !(value2 instanceof Number))
    return null;

  Number num1 = (Number)value1;
  Number num2 = (Number)value2;

  if (inst3 instanceof IADD)
    return num1.intValue() + num2.intValue();
  if (inst3 instanceof ISUB)
    return num1.intValue() - num2.intValue();
  if (inst3 instanceof IMUL)
    return num1.intValue() * num2.intValue();
  if (inst3 instanceof IDIV && num2.intValue() != 0)
    return num1.intValue() / num2.intValue();
  if (inst3 instanceof IREM && num2.intValue() != 0)
    return num1.intValue() % num2.intValue();
  if (inst3 instanceof FADD)
    return num1.floatValue() + num2.floatValue();
  if (inst3 instanceof FSUB)
    return num1.floatValue() - num2.floatValue();
  if (inst3 instanceof FMUL)
    return num1.floatValue() * num2.floatValue();
  if (inst3 instanceof FDIV && num2.floatValue() != 0)
    return num1.floatValue() / num2.floatValue();
  if (inst3 instanceof FREM && num2.floatValue() != 0)
    return num1.floatValue() % num2.floatValue();
  if (inst3 instanceof LADD)
    return num1.longValue() + num2.longValue();
  if (inst3 instanceof LSUB)
    return num1.longValue() - num2.longValue();
  if (inst3 instanceof LMUL)
    return num1.longValue() * num2.longValue();
  if (inst3 instanceof LDIV && num2.longValue() != 0)
    return num1.longValue() / num2.longValue();
  if (inst3 instanceof LREM && num2.longValue() != 0)
    return num1.longValue() % num2.longValue();
  if (inst3 instanceof DADD)
    return num1.doubleValue() + num2.doubleValue();
  if (inst3 instanceof DSUB)
    return num1.doubleValue() - num2.doubleValue();
  if (inst3 instanceof DMUL)
    return num1.doubleValue() * num2.doubleValue();
  if (inst3 instanceof DDIV && num2.doubleValue() != 0)
    return num1.doubleValue() / num2.doubleValue();
  if (inst3 instanceof DREM && num2.doubleValue() != 0)
    return num1.doubleValue() % num2.doubleValue();

  return null;
}

private
Object getLDCValue(Instruction inst, ConstantPoolGen cpgen) {
  if (inst instanceof LDC)
    return ((LDC)inst).getValue(cpgen);
  if (inst instanceof LDC2_W)
    return ((LDC2_W)inst).getValue(cpgen);
  return null;
}

private
Instruction createLDCInstruction(Number value, ConstantPoolGen cpgen) {
  if (value instanceof Integer)
    return new LDC(cpgen.addInteger(value.intValue()));
  if (value instanceof Float)
    return new LDC(cpgen.addFloat(value.floatValue()));
  if (value instanceof Long)
    return new LDC2_W(cpgen.addLong(value.longValue()));
  if (value instanceof Double)
    return new LDC2_W(cpgen.addDouble(value.doubleValue()));
  return null;
}

public
void propagateConstants() {
  Method[] methods = gen.getMethods();
  ConstantPoolGen cpgen = gen.getConstantPool();

  for (Method method : methods) {
    if (method.isAbstract() || method.isNative()) {
      continue;
    }

    MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);
    InstructionList instructionList = methodGen.getInstructionList();
    if (instructionList == null)
      continue;

    InstructionFinder finder = new InstructionFinder(instructionList);
    Map<Integer, Number> constants = new HashMap<>();
    Set<Integer> reassignedVars = new HashSet<>();

    // Identify constant variable assignments (ISTORE, LSTORE, FSTORE, DSTORE)
    for (Iterator<InstructionHandle[]> it =
             finder.search("LDC|LDC2_W ISTORE|LSTORE|FSTORE|DSTORE");
         it.hasNext();) {
      InstructionHandle[] match = it.next();
      if (match.length != 2)
        continue;

      Instruction ldc = match[0].getInstruction();
      Instruction store = match[1].getInstruction();
      int varIndex =
          ((org.apache.bcel.generic.StoreInstruction)store).getIndex();

      if (ldc instanceof LDC) {
        constants.put(varIndex, (Number) ((LDC) ldc).getValue(cpgen));
      } else if (ldc instanceof LDC2_W) {
        constants.put(varIndex, ((LDC2_W)ldc).getValue(cpgen));
      }
    }

    // Find reassignments (excluding the first assignment we stored above)
    for (Iterator<InstructionHandle> it = instructionList.iterator();
         it.hasNext();) {
      InstructionHandle handle = it.next();
      Instruction inst = handle.getInstruction();
      if (inst instanceof org.apache.bcel.generic.StoreInstruction) {
        int varIndex =
            ((org.apache.bcel.generic.StoreInstruction)inst).getIndex();
        if (!constants.containsKey(varIndex)) {
          reassignedVars.add(varIndex);
        }
      }
    }

    // Remove reassigned variables from our constant map
    for (int varIndex : reassignedVars) {
      constants.remove(varIndex);
    }

    // Replace variable loads with constants
    for (Iterator<InstructionHandle> it = instructionList.iterator();
         it.hasNext();) {
      InstructionHandle handle = it.next();
      Instruction inst = handle.getInstruction();
      if (inst instanceof org.apache.bcel.generic.LoadInstruction) {
        int varIndex =
            ((org.apache.bcel.generic.LoadInstruction)inst).getIndex();
        if (constants.containsKey(varIndex)) {
          Number value = constants.get(varIndex);
          Instruction newInst;
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
          } else {
            continue;
          }

          try {
            instructionList.insert(handle, newInst);
            instructionList.delete(handle);
          } catch (TargetLostException e) {
            e.printStackTrace();
          }
        }
      }
    }

    // Update method
    methodGen.setMaxStack();
    methodGen.setMaxLocals();
    gen.replaceMethod(method, methodGen.getMethod());
  }
}

public
void write(String optimisedFilePath) {
  optimize();
  try(FileOutputStream out =
          new FileOutputStream(new File(optimisedFilePath))) {
    optimized.dump(out);
  }
  catch (IOException e) {
    e.printStackTrace();
  }
}
}
