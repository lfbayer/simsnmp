function handleController(source, target, oid)
{
    if (oid === '1.3.6.1.2.1.1.1.0') // sysDescr
    {
        return "Simulator identifying as Cisco IOS";
    }
    else if (oid === '1.3.6.1.2.1.1.2.0') // sysOid
    {
        return ".1.3.6.1.4.1.9.1.29510.3.1";
    }
    else if (oid === '1.3.6.1.2.1.1.5.0') // sysName
    {
        return "Simulator";
    }
    return null;
}

function getnext_controller(source, target, oid)
{
    var oid_prefix = '.1.3.6.1.2.1.14.10.1.1.1.';

    var files = list_logfiles();
    if (files.length == 0)
    {
        return null;
    }

    var ip;

    // Faux OSPF...
    var matches = oid.match(/^1\.3\.6\.1\.2\.1\.14\.10\.1\.1\.1\.(\d+(\.\d+){3})$/);
    if (matches)
    {
        var last_ip = matches[1];
        var found = false;
        for (var i=0; i < files.length; i++)
        {
            var file = files[i];
            if (found)
            {
                matches = file.match(/(\d+(\.\d+){3})\.log$/);
                if (matches)
                {
                    ip = matches[1];
                    break;
                }
            }

            if (file === last_ip + '.log')
            {
                found = true;
                continue;
            }
        }
    }
    else
    {
        file = files[0];
        matches = file.match(/(\d+(\.\d+){3})\.log$/);
        if (matches)
        {
            ip = matches[1];
        }
    }

    if (ip)
    {
        return binding(oid_prefix + ip, ip);
    }

    return binding(oid, null);
}


function get(source, target, oid)
{
    LOGGER.info("get: " + source + "->" + target + " " + oid);
    var value = doGet(source, target, oid);
    LOGGER.info("response: " + value);
    if (value)
    {
        return binding(oid, value);
    }

    return null;
}

var OID_NAMES = {
    'SNMPv2-MIB::sysDescr.0': '.1.3.6.1.2.1.1.1.0',
    'SNMPv2-MIB::sysObjectID.0': '.1.3.6.1.2.1.1.2.0',
    'SNMPv2-MIB::sysName.0': '.1.3.6.1.2.1.1.5.0'
};

function loadSnmpValues(target)
{
    var filename = target + ".snmp";
    if (!new_file(filename).isFile())
    {
        return null;
    }

    var key;
    var result = {};
    var contents = readFile(filename)
    for (var line in contents)
    {
        var keyValue = contents[line].split(' = ', 2);
        if (keyValue.length < 2)
        {
            result[key] = result[key] + "\n" + keyValue[0];
        }
        else
        {
            key = keyValue[0];
            if (OID_NAMES[key])
            {
                key = OID_NAMES[key];
            }

            var value = keyValue[1];
            if (value.startsWith('STRING: '))
            {
                value = value.substring(8);
            }
            else if (value.startsWith('INTEGER: '))
            {
                value = value.substring(9);
            }
            else if (value.startsWith('OID: '))
            {
                value = value.substring(5);
                if (value.startsWith('SNMPv2-SMI::enterprises'))
                {
                    value = '.1.3.6.1.4.1' + value.substring(23);
                }
            }

            result[key] = value;
        }
    }

    return result;
}

function doGet(source, target, oid)
{
    if (target === '10.0.0.121')
    {
        return handleController(source, target, oid);
    }

    var file = new_file(target + ".log");
    if (!file.isFile())
    {
        return null;
    }

    var snmpValues = loadSnmpValues(target);
    if (!snmpValues)
    {
        snmpValues = loadSnmpValues('default');
    }

    return snmpValues['.' + oid];
}

function getnext(source, target, oid)
{
    LOGGER.info("getnext: " + source + "->" + target + " " + oid);
    var new_oid;
    if (oid === "1.3.6.1.2.1.1")
    {
        new_oid = "1.3.6.1.2.1.1.1.0";
    }
    else if (oid === "1.3.6.1.2.1.1.1.0")
    {
        new_oid = "1.3.6.1.2.1.1.2.0";
    }
    else if (oid === "1.3.6.1.2.1.1.2.0")
    {
        new_oid = "1.3.6.1.2.1.1.5.0";
    }
    else
    {
        var value = null;
        if (target === '10.0.0.121')
        {
            value =  getnext_controller(source, target, oid);
            if (value)
            {
                LOGGER.info("response: " + oid + " ==> " + value.getOid() + ":" + value.getVariable().toString());
                return value;
            }
        }

        LOGGER.info("response: " + oid + " ==> endOfMib");
        return binding(oid, value);
    }

    LOGGER.info("getnext: " + oid + " ==> " + new_oid);
    return get(source, target, new_oid);
}

function binding(oid, value)
{
    var variable;
    if (!value)
    {
        variable = org.snmp4j.smi.Null.endOfMibView;
    }
    else if (value.match(/^\d+$/))
    {
        variable = new org.snmp4j.smi.Integer32(value);
    }
    else if (value.match(/^(\.\d+)+$/))
    {
        variable = new org.snmp4j.smi.OID(value);
    }
    else
    {
        variable = new org.snmp4j.smi.OctetString(value);
    }

    return new org.snmp4j.smi.VariableBinding(new org.snmp4j.smi.OID(oid), variable);
}

if (typeof String.prototype.startsWith != 'function') {
    String.prototype.startsWith = function (str){
        return this.indexOf(str) == 0;
    };
}

function readFile(name)
{
    return Java.from(java.nio.file.Files.readAllLines(java.nio.file.Paths.get(name), java.nio.charset.Charset.forName("UTF-8")));
}

function new_file(name)
{
    return new java.io.File(name);
}

function list_logfiles()
{
    var dir = new_file(".");
    var files = dir["list(java.io.FilenameFilter)"](function(dir, name) {
        return name.match("\.log$");
    });

    if (files)
    {
        java.util.Arrays.sort(files, new org.dancer.simsnmp.AlphanumComparator());
        return Java.from(files);
    }

    return null;
}
