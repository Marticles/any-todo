package com.marticles.demo.service;

import com.marticles.demo.service.impl.IDemoService;
import com.marticles.simplemvc.annotation.Service;

@Service
public class DemoService implements IDemoService {

    public String test(String str) {
        return "Service "+str;
    }
}
