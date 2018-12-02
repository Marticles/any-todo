package com.marticles.demo.controller;

import com.marticles.demo.service.impl.IDemoService;
import com.marticles.simplemvc.annotation.Autowired;
import com.marticles.simplemvc.annotation.Controller;
import com.marticles.simplemvc.annotation.RequestMapping;
import com.marticles.simplemvc.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/web")
public class DemoController {

    @Autowired
    IDemoService demoService;

    @RequestMapping("/test")
    public void test(HttpServletRequest request, HttpServletResponse response,
                     @RequestParam("name") String name){
        demoService.test(name);
    }
}
