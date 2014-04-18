package com.openlink.util.json;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;

import com.openlinksw.bibm.Exceptions.ExceptionException;
import com.openlinksw.util.json.Printer;

public class OutPrinter  extends Printer {
    PrintStream out=System.out;
    
    public OutPrinter() {
        super(); // FIXME
    }

    @Override
    public Printer append(char c) {
        out.append(c);
        out.flush();
        return this;
    }

    @Override
    protected Printer append(String s) {
        return append((Object)s);
    }


    public Printer append(Object s) {
        out.append(s.toString());
        out.flush();
       return this;
    }
    
}
