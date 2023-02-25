package de.gurkenlabs.litiengine.input.windows;


import de.gurkenlabs.litiengine.input.*;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.foreign.ValueLayout.*;

public final class DirectInputDeviceProvider implements InputDeviceProvider {
  private static final Logger log = Logger.getLogger(DirectInputDeviceProvider.class.getName());

  public static final int DI8DEVCLASS_GAMECTRL = 4;

  public static final int DIRECTINPUT_VERSION = 0x0800;

  private static final MethodHandle directInput8Create;

  private static final MethodHandle getModuleHandle;

  private final Collection<IDirectInputDevice8> devices = ConcurrentHashMap.newKeySet();

  private final ArrayList<DeviceComponent> currentComponents = new ArrayList<>();

  private final MemorySession memorySession = MemorySession.openConfined();

  static {
    System.loadLibrary("Kernel32");
    System.loadLibrary("dinput8");

    getModuleHandle = downcallHandle("GetModuleHandleW",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    directInput8Create = downcallHandle("DirectInput8Create",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  }

  @Override
  public void collectDevices() {
    this.enumDirectInput8Devices();
  }

  @Override
  public Collection<InputDevice> getDevices() {
    return this.devices.stream().map(x -> x.inputDevice).toList();
  }

  @Override
  public void close() throws IOException {
    for (var device : this.devices) {
      try {
        device.Unacquire();
      } catch (Throwable e) {
        log.log(Level.SEVERE, e.getMessage(), e);
      }
    }

    memorySession.close();
  }

  static MethodHandle downcallHandle(String name, FunctionDescriptor fdesc) {
    return SymbolLookup.loaderLookup().lookup(name).or(() -> Linker.nativeLinker().defaultLookup().lookup(name)).
            map(addr -> Linker.nativeLinker().downcallHandle(addr, fdesc)).
            orElse(null);
  }

  static MethodHandle downcallHandle(MemoryAddress address, FunctionDescriptor fdesc) {
    return Linker.nativeLinker().downcallHandle(address, fdesc);
  }

  private void enumDirectInput8Devices() {
    try {
      // 1. Initialize DirectInput
      var riidltf = memorySession.allocate(GUID.$LAYOUT);
      IDirectInput8.IID_IDirectInput8W.write(riidltf);
      var ppvOut = memorySession.allocate(IDirectInput8.$LAYOUT);

      var directInputCreateResult = (int) directInput8Create.invoke(getModuleHandle.invoke(MemoryAddress.NULL), DIRECTINPUT_VERSION, riidltf, ppvOut, MemoryAddress.NULL);
      if (directInputCreateResult != Result.DI_OK) {
        log.log(Level.SEVERE, "Could not create DirectInput8: " + Result.toString(directInputCreateResult));
        return;
      }

      var directInput = IDirectInput8.read(ppvOut, memorySession);

      // 2. enumerate input devices
      var enumDevicesResult = directInput.EnumDevices(DI8DEVCLASS_GAMECTRL, enumDevicesCallbackNative(memorySession), IDirectInput8.DIEDFL_ALLDEVICES);
      if (enumDevicesResult != Result.DI_OK) {
        log.log(Level.SEVERE, "Could not enumerate DirectInput devices: " + Result.toString(enumDevicesResult));
        return;
      }

      // 3. create devices and enumerate their components
      for (var device : this.devices) {
        currentComponents.clear();
        log.log(Level.INFO, "Found input device: " + device.inputDevice.getInstanceName());

        var deviceAddress = memorySession.allocate(JAVA_LONG.byteSize());
        var deviceGuidMemorySegment = memorySession.allocate(GUID.$LAYOUT);
        device.deviceInstance.guidInstance.write(deviceGuidMemorySegment);

        if (directInput.CreateDevice(deviceGuidMemorySegment, deviceAddress) != Result.DI_OK) {
          log.log(Level.WARNING, "Device " + device.inputDevice.getInstanceName() + " could not be created");
          continue;
        }

        device.create(deviceAddress, memorySession);
        var enumObjectsResult = device.EnumObjects(enumObjectsCallbackNative(memorySession), IDirectInputDevice8.DIDFT_BUTTON | IDirectInputDevice8.DIDFT_AXIS | IDirectInputDevice8.DIDFT_POV);
        if (enumObjectsResult != Result.DI_OK) {
          log.log(Level.WARNING, "Could not enumerate the device instance objects for " + device.inputDevice.getInstanceName() + ": " + Result.toString(enumObjectsResult));
          continue;
        }

        // TODO: Call IDirectInputDevice8::SetDataFormat first or this will always return DIERR_INVALIDPARAM
        var acquireResult = device.Acquire();
        if (acquireResult != Result.DI_OK) {
          log.log(Level.WARNING, "Could not acquire direct input device " + device.inputDevice.getInstanceName() + ": " + Result.toString(acquireResult));
          continue;
        }

        device.inputDevice.addComponents(currentComponents);
      }
    } catch (Throwable e) {
      log.log(Level.SEVERE, e.getMessage(), e);
    }

  }

  private void pollDirectInputDevice(InputDevice inputDevice) {
    // find native DirectInputDevice and poll it
    var directInputDevice = this.devices.stream().filter(x -> x.inputDevice.equals(inputDevice)).findFirst().orElse(null);
    if (directInputDevice == null) {
      log.log(Level.WARNING, "DirectInput device not found for input device " + inputDevice.getInstanceName());
      return;
    }

    try {
      var pollResult = directInputDevice.Poll();
      if (pollResult != Result.DI_OK) {
        log.log(Level.WARNING, "Could not poll device " + inputDevice.getInstanceName() + ": " + Result.toString(pollResult));
      }
    } catch (Throwable e) {
      log.log(Level.SEVERE, e.getMessage(), e);
    }
  }

  /**
   * This is called out of native code while enumerating the available devices.
   *
   * @param lpddiSegment The pointer to the {@link DIDEVICEINSTANCE} address.
   * @param pvRef        An application specific reference pointer (not used by our library).
   * @return True to indicate for the native code to continue with the enumeration otherwise false.
   */
  private boolean enumDevicesCallback(MemoryAddress lpddiSegment, MemoryAddress pvRef) {
    var deviceInstance = DIDEVICEINSTANCE.read(MemorySegment.ofAddress(lpddiSegment, DIDEVICEINSTANCE.$LAYOUT.byteSize(), memorySession));
    var name = new String(deviceInstance.tszInstanceName).trim();
    var product = new String(deviceInstance.tszProductName).trim();
    var type = DI8DEVTYPE.fromDwDevType(deviceInstance.dwDevType);

    // for now, we're only interested in gamepads, will add other types later
    if (type == DI8DEVTYPE.DI8DEVTYPE_GAMEPAD || type == DI8DEVTYPE.DI8DEVTYPE_JOYSTICK) {
      var inputDevice = new InputDevice(deviceInstance.guidInstance.toUUID(), deviceInstance.guidProduct.toUUID(), name, product, this::pollDirectInputDevice);
      this.devices.add(new IDirectInputDevice8(deviceInstance, inputDevice));
    } else {
      log.log(Level.WARNING, "found device that is not a gamepad or joystick: " + name + "[" + type + "]");
    }

    return true;
  }

  /**
   * This is called out of native code while enumerating the available objects of a device.
   *
   * @param lpddoiSegment The pointer to the {@link DIDEVICEOBJECTINSTANCE} address.
   * @param pvRef         An application specific reference pointer (not used by our library).
   * @return True to indicate for the native code to continue with the enumeration otherwise false.
   */
  private boolean enumObjectsCallback(MemoryAddress lpddoiSegment, MemoryAddress pvRef) {
    var deviceObjectInstance = DIDEVICEOBJECTINSTANCE.read(MemorySegment.ofAddress(lpddoiSegment, DIDEVICEINSTANCE.$LAYOUT.byteSize(), memorySession));
    var name = new String(deviceObjectInstance.tszName).trim();
    var deviceObjectType = DI8DEVOBJECTTYPE.from(deviceObjectInstance.guidType);

    var component = new DeviceComponent(ComponentType.valueOf(deviceObjectType.name()), name);
    this.currentComponents.add(component);
    log.log(Level.FINE, "\t\t" + deviceObjectType + " (" + name + ") - " + deviceObjectInstance.dwOfs);
    return true;
  }

  // passed to native code for callback
  private MemorySegment enumDevicesCallbackNative(MemorySession memorySession) throws Throwable {
    // Create a method handle to the Java function as a callback
    MethodHandle onEnumDevices = MethodHandles.lookup()
            .bind(this, "enumDevicesCallback", MethodType.methodType(boolean.class, MemoryAddress.class, MemoryAddress.class));

    return Linker.nativeLinker().upcallStub(
            onEnumDevices, FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS), memorySession);
  }

  // passed to native code for callback
  private MemorySegment enumObjectsCallbackNative(MemorySession memorySession) throws Throwable {
    // Create a method handle to the Java function as a callback
    MethodHandle onEnumDevices = MethodHandles.lookup()
            .bind(this, "enumObjectsCallback", MethodType.methodType(boolean.class, MemoryAddress.class, MemoryAddress.class));

    return Linker.nativeLinker().upcallStub(
            onEnumDevices, FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS), memorySession);
  }

  private static class Result {
    static final int DI_OK = 0x00000000;
    static final int DIERR_INVALIDPARAM = 0x80070057;
    static final int DIERR_NOTINITIALIZED = 0x80070015;
    static final int DI_BUFFEROVERFLOW = 0x00000001;
    static final int DIERR_INPUTLOST = 0x8007001E;
    static final int DIERR_NOTACQUIRED = 0x8007000C;
    static final int DIERR_OTHERAPPHASPRIO = 0x80070005;

    static String toString(int HRESULT) {
      var hexResult = String.format("%08X", HRESULT);
      return switch (HRESULT) {
        case DI_OK -> "DI_OK: " + hexResult;
        case DIERR_INVALIDPARAM -> "DIERR_INVALIDPARAM: " + hexResult;
        case DIERR_NOTINITIALIZED -> "DIERR_NOTINITIALIZED: " + hexResult;
        case DI_BUFFEROVERFLOW -> "DI_BUFFEROVERFLOW: " + hexResult;
        case DIERR_INPUTLOST -> "DIERR_INPUTLOST: " + hexResult;
        case DIERR_NOTACQUIRED -> "DIERR_NOTACQUIRED: " + hexResult;
        case DIERR_OTHERAPPHASPRIO -> "DIERR_OTHERAPPHASPRIO: " + hexResult;

        default -> hexResult;
      };

    }
  }
}
